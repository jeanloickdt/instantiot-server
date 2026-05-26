// ============================================================
// InstantIoT — Admin Dashboard (Alpine.js)
// ============================================================

document.addEventListener('alpine:init', () => {
  Alpine.data('app', () => ({

    // ── State ──────────────────────────────────────────────
    view: 'login',
    token: localStorage.getItem('token'),
    role: localStorage.getItem('role'),
    // Legacy flag — the server no longer sends it (V1 first-launch flow).
    // We default to 'true' (= setup done, goes straight to dashboard) if absent
    // or if stored as a value different from 'false'. The 'setup' screen
    // remains accessible if the (old) server explicitly returns false,
    // or if a legacy install stored 'false' in localStorage.
    passwordChanged: localStorage.getItem('passwordChanged') !== 'false',
    lang: localStorage.getItem('lang') || 'en',
    theme: localStorage.getItem('theme') || null,
    refreshInterval: null,
    showRestartModal: false,

    // ── Sidebar / mobile drawer ────────────────────────────
    sidebarCollapsed: localStorage.getItem('sidebarCollapsed') === 'true',
    mobileMenuOpen: false,

    // ── Backups list display (Show all / Show less) ───────
    showAllBackups: false,

    // ── Forms ──────────────────────────────────────────────
    loginForm: { username: '', password: '', error: '' },
    setupForm: { current: '', newPwd: '', confirm: '', error: '' },

    configForm: {
      httpPort: 8080,
      tcpPort: 9001,
      serverDisplayName: '',
      effectiveDisplayName: '',  // shown as placeholder when blank
      msg: '', msgType: ''
    },
    historyForm: {
      rawEnabled: false,
      retentionRawDays: 7,
      retentionOpaqueDays: 1,
      retentionMinDays: 90,
      retentionHourDays: 365,
      retentionDayDays: -1,
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
    registrationForm: {
      open: false,
      busy: false,
      msg: '', msgType: ''
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
    users: [],
    resetUserForm: {
      targetUser: null,
      newPassword: '',
      busy: false,
      error: ''
    },

    // ── Computed ────────────────────────────────────────────
    get themeIcon() {
      const effective = this.theme
          || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
      return effective === 'dark' ? '\u2600\uFE0F' : '\uD83C\uDF19';
    },

    get effectiveTheme() {
      return this.theme
          || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
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

    // ── Sidebar ────────────────────────────────────────────
    toggleSidebar() {
      this.sidebarCollapsed = !this.sidebarCollapsed;
      localStorage.setItem('sidebarCollapsed', String(this.sidebarCollapsed));
    },

    // ── Init ───────────────────────────────────────────────
    // V1.3: no more license system or first-launch flow.
    // The server always boots ready (admin account created at boot).
    // → if we have a valid admin token, we enter the dashboard,
    //   otherwise we display the login.
    async init() {
      if (this.theme) {
        document.documentElement.setAttribute('data-theme', this.theme);
      }
      document.documentElement.lang = this.lang;

      if (this.token && this.role === 'admin') {
        if (!this.passwordChanged) {
          this.view = 'setup';
        } else {
          this.enterDashboard();
        }
      } else {
        this.view = 'login';
      }
    },

    // ── API helper ─────────────────────────────────────────
    async api(path, options = {}) {
      const headers = { 'Content-Type': 'application/json', ...options.headers };
      if (this.token) headers['Authorization'] = `Bearer ${this.token}`;
      const res = await fetch(path, { ...options, headers });
      if (res.status === 401) { this.logout(); return null; }
      return res;
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
        // Tolerates missing field (V1 server) → considers setup done
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

    async loadUsers() {
      const res = await this.api('/api/admin/users');
      if (!res || !res.ok) return;
      const data = await res.json();
      this.users = data.users || [];
    },

    async loadRegistrationConfig() {
      const res = await this.api('/api/admin/registration/config');
      if (!res || !res.ok) return;
      const data = await res.json();
      this.registrationForm.open = !!data.open;
      this.registrationForm.msg = '';
      this.registrationForm.msgType = '';
    },

    async saveRegistrationConfig() {
      this.registrationForm.busy = true;
      this.registrationForm.msg = '';
      this.registrationForm.msgType = '';
      const res = await this.api('/api/admin/registration/config', {
        method: 'PATCH',
        body: JSON.stringify({ open: this.registrationForm.open })
      });
      this.registrationForm.busy = false;
      if (res && res.ok) {
        const data = await res.json().catch(() => null);
        if (data) this.registrationForm.open = !!data.open;
        this.registrationForm.msg = this.t('reg.saved');
        this.registrationForm.msgType = 'success';
      } else if (res) {
        const data = await res.json().catch(() => null);
        this.registrationForm.msg = data?.error || this.t('reg.saveError');
        this.registrationForm.msgType = 'error';
        // revert the visual toggle if the save failed
        await this.loadRegistrationConfig();
      }
    },

    askResetUserPassword(user) {
      this.resetUserForm.targetUser = user;
      this.resetUserForm.newPassword = '';
      this.resetUserForm.error = '';
    },

    cancelResetUserPassword() {
      this.resetUserForm.targetUser = null;
      this.resetUserForm.newPassword = '';
      this.resetUserForm.error = '';
    },

    async confirmResetUserPassword() {
      const user = this.resetUserForm.targetUser;
      if (!user || this.resetUserForm.busy) return;
      const pwd = this.resetUserForm.newPassword;
      if (!pwd || pwd.length < 8) {
        this.resetUserForm.error = this.t('users.passwordTooShort');
        return;
      }
      this.resetUserForm.busy = true;
      try {
        const res = await this.api(`/api/admin/users/${user.id}/reset-password`, {
          method: 'POST',
          body: JSON.stringify({ newPassword: pwd })
        });
        if (res && res.ok) {
          this.cancelResetUserPassword();
        } else if (res) {
          const data = await res.json().catch(() => null);
          this.resetUserForm.error = data?.error || this.t('users.resetError');
        }
      } finally {
        this.resetUserForm.busy = false;
      }
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
      this.historyForm.rawEnabled          = !!data.rawEnabled;
      this.historyForm.retentionRawDays    = data.retentionRawDays;
      this.historyForm.retentionOpaqueDays = data.retentionOpaqueDays;
      this.historyForm.retentionMinDays    = data.retentionMinDays;
      this.historyForm.retentionHourDays   = data.retentionHourDays;
      this.historyForm.retentionDayDays    = data.retentionDayDays;
      this.historyForm.msg = '';
      this.historyForm.msgType = '';
    },

    async saveHistoryConfig() {
      this.historyForm.msg = '';
      this.historyForm.msgType = '';

      const res = await this.api('/api/admin/history-config', {
        method: 'PATCH',
        body: JSON.stringify({
          rawEnabled:          this.historyForm.rawEnabled,
          retentionRawDays:    this.historyForm.retentionRawDays,
          retentionOpaqueDays: this.historyForm.retentionOpaqueDays,
          retentionMinDays:    this.historyForm.retentionMinDays,
          retentionHourDays:   this.historyForm.retentionHourDays,
          retentionDayDays:    this.historyForm.retentionDayDays
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
          // Force the user to restart now
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
      switch (target) {
        case 'dashboard':
          this.loadDashboard();
          break;
        case 'devices':
          this.loadDevices();
          break;
        case 'users':
          this.loadUsers();
          this.loadRegistrationConfig();
          break;
        case 'settings':
          this.loadServerInfo();
          break;
        case 'retention':
          this.loadHistoryConfig();
          break;
        case 'backups':
          this.loadBackupConfig();
          this.loadBackupList();
          break;
      }
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
      if (!text) return;
      try {
        await navigator.clipboard.writeText(text);
      } catch (_) { return; }

      // If the button contains an <svg> (new design), swap the icon
      // to #icon-check temporarily. Otherwise (old fallback), replace
      // textContent as before.
      const useEl = btn.querySelector('svg use');
      if (useEl) {
        const original = useEl.getAttribute('href') || useEl.getAttribute('xlink:href');
        const setHref = (h) => {
          useEl.setAttribute('href', h);
          useEl.setAttribute('xlink:href', h);
        };
        btn.classList.add('copied');
        setHref('#icon-check');
        setTimeout(() => {
          btn.classList.remove('copied');
          if (original) setHref(original);
        }, 1500);
      } else {
        const original = btn.textContent;
        btn.textContent = '\u2705';
        setTimeout(() => { btn.textContent = original; }, 1500);
      }
    }

  }));
});