// ============================================================
// InstantIoT — Admin Dashboard (Alpine.js)
// ============================================================

document.addEventListener('alpine:init', () => {
  Alpine.data('app', () => ({

    // ── State ──────────────────────────────────────────────
    view: 'login',
    token: localStorage.getItem('token'),
    role: localStorage.getItem('role'),
    // Legacy flag — le serveur ne l'envoie plus (V1 first-launch flow).
    // On default à 'true' (= setup done, va direct au dashboard) si absent
    // ou si stocké à une valeur différente de 'false'. L'écran 'setup'
    // reste accessible si le serveur (ancien) renvoie explicitement false,
    // ou si une install legacy avait stocké 'false' dans localStorage.
    passwordChanged: localStorage.getItem('passwordChanged') !== 'false',
    lang: localStorage.getItem('lang') || 'en',
    theme: localStorage.getItem('theme') || null,
    refreshInterval: null,
    showRestartModal: false,

    // ── Forms ──────────────────────────────────────────────
    licenceForm: { key: '', error: '' },
    loginForm: { username: '', password: '', error: '' },
    setupForm: { current: '', newPwd: '', confirm: '', error: '' },

    // ── Welcome (V1 first-launch) ──────────────────────────
    // bootstrapLicenceId : licence.id remontée par POST /api/licence,
    // affichée sur l'écran welcome comme default password
    bootstrapLicenceId: '',
    welcomeForm: { username: '', password: '', error: '', submitting: false },

    // ── Forgot password ─────────────────────────────────────
    forgotForm: { key: '', error: '', success: false },
    configForm: {
      httpPort: 8080,
      tcpPort: 9001,
      serverDisplayName: '',
      effectiveDisplayName: '',  // shown as placeholder when blank
      msg: '', msgType: ''
    },
    historyForm: {
      retentionRawDays: 7,
      retentionOpaqueDays: 1,
      throttleRawIntervalSeconds: 5,
      retentionMinDays: 90,
      retentionHourDays: 365,
      retentionDayDays: -1,
      downsampleIntervalMinutes: 60,
      msg: '', msgType: ''
    },
    backupForm: {
      enabled: true,
      intervalHours: 24,
      retentionCount: 30,
      lastBackupAtMs: 0,
      backupCount: 0,
      backupDirPath: '',
      backups: [],
      busy: false,           // disable controls during snapshot/restore
      msg: '', msgType: '',
      restoreModalFor: null  // filename in modal, null = closed
    },

    // ── Data ───────────────────────────────────────────────
    stats: {
      users: '-', projects: '-', devicesTotal: '-',
      devicesOnline: '-', appSessionsActive: '-', deviceSessionsActive: '-'
    },
    serverInfo: {
      localIp: '-', httpPort: '-', tcpPort: '-', uptimeMs: 0,
      version: '-', dbSizeBytes: 0, javaVersion: '-', osName: '-'
    },
    devices: [],

    // ── Computed ────────────────────────────────────────────
    get themeIcon() {
      const effective = this.theme
        || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
      return effective === 'dark' ? '\u2600\uFE0F' : '\uD83C\uDF19';
    },

    // ── i18n ───────────────────────────────────────────────
    t(key) { return t(key, this.lang); },

    setLang(lang) {
      this.lang = lang;
      localStorage.setItem('lang', lang);
      document.documentElement.lang = lang;
    },

    // ── Theme ──────────────────────────────────────────────
    toggleTheme() {
      const current = this.theme
        || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
      this.theme = current === 'dark' ? 'light' : 'dark';
      document.documentElement.setAttribute('data-theme', this.theme);
      localStorage.setItem('theme', this.theme);
    },

    // ── Init ───────────────────────────────────────────────
    // Routing V1 first-launch flow basé sur GET /api/status :
    //   setup_state = "needs_licence" → /licence (saisie de la clé)
    //   setup_state = "needs_welcome" → /welcome (set credentials)
    //   setup_state = "ready"          → /login ou /dashboard si token
    //
    // Fallback compat : si /api/status ne renvoie pas setup_state
    // (ancien serveur), on retombe sur les booléens legacy
    // licence_required + setup_required.
    async init() {
      if (this.theme) {
        document.documentElement.setAttribute('data-theme', this.theme);
      }
      document.documentElement.lang = this.lang;

      const status = await this.fetchStatus();
      const state = this.deriveSetupState(status);

      if (state === 'needs_licence') {
        this.view = 'licence';
        return;
      }
      if (state === 'needs_welcome') {
        // Si on a un token (auto-login post-activation), on peut
        // aller direct sur welcome. Sinon il faut passer par licence
        // → welcome (cas du browser fraîchement ouvert sur un serveur
        // dont le welcome n'a pas encore été acté).
        if (this.token) {
          this.view = 'welcome';
        } else {
          // Pas de token → forcer licence pour qu'activate ré-émette
          // le token auto-login. Ce cas est rare (il faudrait que
          // l'user ait fermé le browser entre activation et welcome).
          this.view = 'licence';
        }
        return;
      }

      // setup_state = "ready"
      if (this.token && this.role === 'admin') {
        if (!this.passwordChanged) {
          // Compat ancien serveur — sera dead path en V1
          this.view = 'setup';
        } else {
          this.enterDashboard();
        }
      } else {
        this.view = 'login';
      }
    },

    /**
     * Récupère /api/status et retourne l'objet ou null si pas joignable.
     */
    async fetchStatus() {
      try {
        const res = await fetch('/api/status');
        if (!res.ok) return null;
        return await res.json();
      } catch (_) {
        return null;
      }
    },

    /**
     * Dérive le setup_state depuis la réponse /api/status. Privilège le
     * champ moderne `setup_state` ; sinon fallback sur les booléens
     * legacy (rétro-compat ancien serveur).
     */
    deriveSetupState(status) {
      if (!status) return 'needs_licence';   // serveur down → on assume worst case
      if (status.setup_state) return status.setup_state;
      if (status.licence_required) return 'needs_licence';
      if (status.setup_required)   return 'needs_welcome';
      return 'ready';
    },

    // ── API helper ─────────────────────────────────────────
    async api(path, options = {}) {
      const headers = { 'Content-Type': 'application/json', ...options.headers };
      if (this.token) headers['Authorization'] = `Bearer ${this.token}`;
      const res = await fetch(path, { ...options, headers });
      if (res.status === 401) { this.logout(); return null; }
      return res;
    },

    // ── Licence ─────────────────────────────────────────────
    async checkLicence() {
      try {
        const res = await fetch('/api/licence');
        return res.ok;
      } catch (_) {
        return false;
      }
    },

    async activateLicence() {
      this.licenceForm.error = '';
      const key = this.licenceForm.key.trim();
      if (!key) {
        this.licenceForm.error = this.t('licence.empty');
        return;
      }

      try {
        const res = await fetch('/api/licence', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ key })
        });

        if (!res.ok) {
          this.licenceForm.error = this.t('licence.invalid');
          return;
        }

        const data = await res.json();
        // Stocker l'id pour l'écran welcome qui l'affiche comme
        // default password
        this.bootstrapLicenceId = data.id || '';

        if (data.token) {
          // Bootstrap admin déclenché par l'activation — auto-login
          // pour enchainer setup → welcome sans friction
          this.token = data.token;
          this.role = 'admin';
          this.passwordChanged = true;  // pas de force-change V1
          localStorage.setItem('token', data.token);
          localStorage.setItem('role', 'admin');
          localStorage.setItem('passwordChanged', 'true');
          this.view = 'welcome';
        } else {
          // Re-activation sur serveur déjà setup — pas de token,
          // l'user passe par login normal
          this.view = 'login';
        }

        this.licenceForm.key = '';   // wipe input
      } catch (_) {
        this.licenceForm.error = this.t('licence.error');
      }
    },

    // ── Forgot password (V1 first-launch) ───────────────────
    async submitForgot() {
      this.forgotForm.error = '';
      const key = this.forgotForm.key.trim();
      if (!key) {
        this.forgotForm.error = this.t('forgot.empty');
        return;
      }
      try {
        const res = await fetch('/api/setup/forgot-password', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ licenceKey: key })
        });
        if (res.ok) {
          this.forgotForm.success = true;
          this.forgotForm.key = '';
        } else {
          let msg = this.t('forgot.invalid');
          try { msg = (await res.json()).error || msg; } catch (_) {}
          this.forgotForm.error = msg;
        }
      } catch (_) {
        this.forgotForm.error = this.t('forgot.error');
      }
    },

    // ── Welcome (V1 first-launch) ──────────────────────────
    async submitWelcome(action) {
      if (this.welcomeForm.submitting) return;
      this.welcomeForm.error = '';

      const body = { action };
      if (action === 'renew') {
        const u = this.welcomeForm.username.trim();
        const p = this.welcomeForm.password;
        if (!u && !p) {
          this.welcomeForm.error = this.t('welcome.atLeastOne');
          return;
        }
        if (u) body.username = u;
        if (p) body.password = p;
      }

      this.welcomeForm.submitting = true;
      try {
        const res = await this.api('/api/setup/welcome', {
          method: 'POST',
          body: JSON.stringify(body)
        });
        if (!res) return;   // 401 → logout déjà déclenché par api()

        if (res.ok) {
          this.welcomeForm = { username: '', password: '', error: '', submitting: false };
          this.bootstrapLicenceId = '';
          this.enterDashboard();
        } else {
          let errMsg = this.t('welcome.error');
          try { errMsg = (await res.json()).error || errMsg; } catch (_) {}
          this.welcomeForm.error = errMsg;
        }
      } catch (_) {
        this.welcomeForm.error = this.t('welcome.error');
      } finally {
        this.welcomeForm.submitting = false;
      }
    },

    // ── Auth ───────────────────────────────────────────────
    async login() {
      this.loginForm.error = '';
      const res = await fetch('/api/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          username: this.loginForm.username,
          password: this.loginForm.password
        })
      });

      if (res.ok) {
        const data = await res.json();
        this.token = data.token;
        this.role = data.role;
        // Tolère absence du champ (V1 server) → considère setup done
        this.passwordChanged = data.passwordChanged !== false;
        localStorage.setItem('token', data.token);
        localStorage.setItem('role', data.role);
        localStorage.setItem('passwordChanged', String(this.passwordChanged));

        if (this.role !== 'admin') {
          this.logout();
          this.loginForm.error = this.t('login.adminOnly');
          return;
        }

        if (!this.passwordChanged) {
          this.view = 'setup';
        } else {
          this.enterDashboard();
        }
      } else {
        this.loginForm.error = this.t('login.error');
      }
    },

    logout() {
      this.token = null;
      this.role = null;
      this.passwordChanged = false;
      localStorage.removeItem('token');
      localStorage.removeItem('role');
      localStorage.removeItem('passwordChanged');
      if (this.refreshInterval) clearInterval(this.refreshInterval);
      this.view = 'login';
      this.loginForm = { username: '', password: '', error: '' };
    },

    // ── Change password ────────────────────────────────────
    async changePassword() {
      this.setupForm.error = '';

      if (this.setupForm.newPwd !== this.setupForm.confirm) {
        this.setupForm.error = this.t('setup.mismatch');
        return;
      }
      if (this.setupForm.newPwd.length < 8) {
        this.setupForm.error = this.t('setup.tooShort');
        return;
      }

      const res = await this.api('/api/users/me/password', {
        method: 'PATCH',
        body: JSON.stringify({
          currentPassword: this.setupForm.current,
          newPassword: this.setupForm.newPwd
        })
      });

      if (res && res.ok) {
        this.passwordChanged = true;
        localStorage.setItem('passwordChanged', 'true');
        this.enterDashboard();
      } else if (res) {
        const data = await res.json().catch(() => null);
        this.setupForm.error = data?.error || this.t('setup.error');
      }
    },

    // ── Dashboard ──────────────────────────────────────────
    enterDashboard() {
      this.view = 'dashboard';
      this.loadDashboard();
      if (this.refreshInterval) clearInterval(this.refreshInterval);
      this.refreshInterval = setInterval(() => this.loadDashboard(), 10000);
    },

    async loadDashboard() {
      this.loadStats();
      this.loadServerInfo();
      this.loadHistoryConfig();
      this.loadBackupConfig();
      this.loadBackupList();
    },

    async loadStats() {
      const res = await this.api('/api/admin/stats');
      if (!res || !res.ok) return;
      this.stats = await res.json();
    },

    async loadServerInfo() {
      const res = await this.api('/api/admin/server-info');
      if (!res || !res.ok) return;
      this.serverInfo = await res.json();
      this.configForm.httpPort = this.serverInfo.httpPort;
      this.configForm.tcpPort = this.serverInfo.tcpPort;
      this.configForm.serverDisplayName = this.serverInfo.serverDisplayName || '';
      this.configForm.effectiveDisplayName = this.serverInfo.effectiveDisplayName || '';
    },

    // ── Config ─────────────────────────────────────────────
    async saveConfig() {
      this.configForm.msg = '';
      this.configForm.msgType = '';

      const res = await this.api('/api/admin/config', {
        method: 'PATCH',
        body: JSON.stringify({
          httpPort: this.configForm.httpPort,
          tcpPort: this.configForm.tcpPort,
          serverDisplayName: this.configForm.serverDisplayName
        })
      });

      if (res && res.ok) {
        this.configForm.msg = this.t('config.saved');
        this.configForm.msgType = 'success';
      } else if (res) {
        const data = await res.json().catch(() => null);
        this.configForm.msg = data?.error || this.t('config.saveError');
        this.configForm.msgType = 'error';
      }
    },

    // ── History config ─────────────────────────────────────
    async loadHistoryConfig() {
      const res = await this.api('/api/admin/history-config');
      if (!res || !res.ok) return;
      const data = await res.json();
      this.historyForm.retentionRawDays           = data.retentionRawDays;
      this.historyForm.retentionOpaqueDays        = data.retentionOpaqueDays;
      this.historyForm.throttleRawIntervalSeconds = data.throttleRawIntervalSeconds;
      this.historyForm.retentionMinDays           = data.retentionMinDays;
      this.historyForm.retentionHourDays          = data.retentionHourDays;
      this.historyForm.retentionDayDays           = data.retentionDayDays;
      this.historyForm.downsampleIntervalMinutes  = data.downsampleIntervalMinutes;
      this.historyForm.msg = '';
      this.historyForm.msgType = '';
    },

    async saveHistoryConfig() {
      this.historyForm.msg = '';
      this.historyForm.msgType = '';

      const res = await this.api('/api/admin/history-config', {
        method: 'PATCH',
        body: JSON.stringify({
          retentionRawDays:           this.historyForm.retentionRawDays,
          retentionOpaqueDays:        this.historyForm.retentionOpaqueDays,
          throttleRawIntervalSeconds: this.historyForm.throttleRawIntervalSeconds,
          retentionMinDays:           this.historyForm.retentionMinDays,
          retentionHourDays:          this.historyForm.retentionHourDays,
          retentionDayDays:           this.historyForm.retentionDayDays,
          downsampleIntervalMinutes:  this.historyForm.downsampleIntervalMinutes
        })
      });

      if (res && res.ok) {
        this.historyForm.msg = this.t('history.saved');
        this.historyForm.msgType = 'success';
      } else if (res) {
        const data = await res.json().catch(() => null);
        this.historyForm.msg = data?.error || this.t('history.saveError');
        this.historyForm.msgType = 'error';
      }
    },

    // ── Backup config ──────────────────────────────────────
    async loadBackupConfig() {
      const res = await this.api('/api/admin/backup/config');
      if (!res || !res.ok) return;
      const data = await res.json();
      this.backupForm.enabled        = data.enabled;
      this.backupForm.intervalHours  = data.intervalHours;
      this.backupForm.retentionCount = data.retentionCount;
      this.backupForm.lastBackupAtMs = data.lastBackupAtMs;
      this.backupForm.backupCount    = data.backupCount;
      this.backupForm.backupDirPath  = data.backupDirPath;
    },

    async loadBackupList() {
      const res = await this.api('/api/admin/backup/list');
      if (!res || !res.ok) return;
      const data = await res.json();
      this.backupForm.backups = data.backups || [];
    },

    async saveBackupConfig() {
      this.backupForm.msg = '';
      this.backupForm.msgType = '';
      const res = await this.api('/api/admin/backup/config', {
        method: 'PATCH',
        body: JSON.stringify({
          enabled:        this.backupForm.enabled,
          intervalHours:  this.backupForm.intervalHours,
          retentionCount: this.backupForm.retentionCount
        })
      });
      if (res && res.ok) {
        this.backupForm.msg = this.t('backup.saved');
        this.backupForm.msgType = 'success';
      } else if (res) {
        const data = await res.json().catch(() => null);
        this.backupForm.msg = data?.error || this.t('backup.saveError');
        this.backupForm.msgType = 'error';
      }
    },

    async backupNow() {
      if (this.backupForm.busy) return;
      this.backupForm.busy = true;
      this.backupForm.msg = this.t('backup.snapshotting');
      this.backupForm.msgType = 'success';
      try {
        const res = await this.api('/api/admin/backup/now', { method: 'POST' });
        if (res && res.ok) {
          this.backupForm.msg = this.t('backup.snapshotOk');
          await this.loadBackupConfig();
          await this.loadBackupList();
        } else if (res) {
          const data = await res.json().catch(() => null);
          this.backupForm.msg = data?.error || this.t('backup.snapshotFail');
          this.backupForm.msgType = 'error';
        }
      } finally {
        this.backupForm.busy = false;
      }
    },

    askRestore(filename) {
      this.backupForm.restoreModalFor = filename;
    },

    cancelRestore() {
      this.backupForm.restoreModalFor = null;
    },

    async confirmRestore() {
      const filename = this.backupForm.restoreModalFor;
      if (!filename || this.backupForm.busy) return;
      this.backupForm.busy = true;
      this.backupForm.msg = this.t('backup.restoring');
      this.backupForm.msgType = 'success';
      try {
        const res = await this.api('/api/admin/backup/restore', {
          method: 'POST',
          body: JSON.stringify({ filename })
        });
        if (res && res.ok) {
          const data = await res.json();
          this.backupForm.msg = data.message ||
            this.t('backup.restoreOk');
          // Force le user à restart maintenant
          this.backupForm.restoreModalFor = null;
          this.showRestartModal = true;
        } else if (res) {
          const data = await res.json().catch(() => null);
          this.backupForm.msg = data?.error || this.t('backup.restoreFail');
          this.backupForm.msgType = 'error';
        }
      } finally {
        this.backupForm.busy = false;
      }
    },

    formatBackupSize(bytes) {
      if (bytes < 1024) return bytes + ' B';
      if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
      return (bytes / 1024 / 1024).toFixed(1) + ' MB';
    },

    formatBackupDate(ms) {
      if (!ms) return this.t('backup.never');
      const d = new Date(ms);
      return d.toLocaleString();
    },

    // ── Restart ────────────────────────────────────────────
    restartServer() {
      this.showRestartModal = true;
    },

    async confirmRestart() {
      this.showRestartModal = false;
      try {
        await this.api('/api/admin/restart', { method: 'POST' });
      } catch (_) { /* connexion coupee — normal */ }
      this.view = 'login';
      this.token = null;
      localStorage.removeItem('token');
      localStorage.removeItem('role');
      localStorage.removeItem('passwordChanged');
      this.loginForm.error = this.t('restart.disconnected');
    },

    // ── Devices ────────────────────────────────────────────
    async loadDevices() {
      const res = await this.api('/api/admin/devices');
      if (!res || !res.ok) return;
      this.devices = await res.json();
    },

    // ── Navigation ─────────────────────────────────────────
    navigate(target) {
      this.view = target;
      if (target === 'dashboard') this.loadDashboard();
      if (target === 'devices') this.loadDevices();
    },

    // ── Utils ──────────────────────────────────────────────
    formatDate(ts) {
      if (!ts) return '-';
      const d = new Date(ts);
      return d.toLocaleDateString(this.lang === 'fr' ? 'fr-FR' : 'en-US')
        + ' ' + d.toLocaleTimeString(this.lang === 'fr' ? 'fr-FR' : 'en-US');
    },

    formatUptime(ms) {
      if (!ms) return '-';
      const s = Math.floor(ms / 1000);
      const m = Math.floor(s / 60);
      const h = Math.floor(m / 60);
      const d = Math.floor(h / 24);
      const ud = this.t('uptime.days'), uh = this.t('uptime.hours');
      const um = this.t('uptime.minutes'), us = this.t('uptime.seconds');
      if (d > 0) return d + ud + ' ' + (h % 24) + uh + ' ' + (m % 60) + um;
      if (h > 0) return h + uh + ' ' + (m % 60) + um;
      if (m > 0) return m + um + ' ' + (s % 60) + us;
      return s + us;
    },

    formatBytes(bytes) {
      if (!bytes) return '0 B';
      const k = 1024;
      const sizes = ['B', 'KB', 'MB', 'GB'];
      const i = Math.floor(Math.log(bytes) / Math.log(k));
      return (bytes / Math.pow(k, i)).toFixed(1) + ' ' + sizes[i];
    },

    async copyText(text, btn) {
      await navigator.clipboard.writeText(text);
      const original = btn.textContent;
      btn.textContent = '\u2705';
      setTimeout(() => btn.textContent = original, 1500);
    }

  }));
});
