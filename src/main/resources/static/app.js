// ============================================================
// InstantIoT — Admin Dashboard (Alpine.js)
// ============================================================

document.addEventListener('alpine:init', () => {
  Alpine.data('app', () => ({

    // ── State ──────────────────────────────────────────────
    view: 'login',
    token: localStorage.getItem('token'),
    role: localStorage.getItem('role'),
    passwordChanged: localStorage.getItem('passwordChanged') === 'true',
    lang: localStorage.getItem('lang') || 'en',
    theme: localStorage.getItem('theme') || null,
    refreshInterval: null,
    showRestartModal: false,

    // ── Forms ──────────────────────────────────────────────
    licenceForm: { key: '', error: '' },
    loginForm: { username: '', password: '', error: '' },
    setupForm: { current: '', newPwd: '', confirm: '', error: '' },
    configForm: { httpPort: 8080, tcpPort: 9001, msg: '', msgType: '' },

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
    async init() {
      if (this.theme) {
        document.documentElement.setAttribute('data-theme', this.theme);
      }
      document.documentElement.lang = this.lang;

      // verifier la licence d'abord
      const licenceOk = await this.checkLicence();
      if (!licenceOk) {
        this.view = 'licence';
        return;
      }

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

        if (res.ok) {
          // licence activee — passer au login
          this.view = 'login';
        } else {
          this.licenceForm.error = this.t('licence.invalid');
        }
      } catch (_) {
        this.licenceForm.error = this.t('licence.error');
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
        this.passwordChanged = data.passwordChanged;
        localStorage.setItem('token', data.token);
        localStorage.setItem('role', data.role);
        localStorage.setItem('passwordChanged', String(data.passwordChanged));

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
    },

    // ── Config ─────────────────────────────────────────────
    async saveConfig() {
      this.configForm.msg = '';
      this.configForm.msgType = '';

      const res = await this.api('/api/admin/config', {
        method: 'PATCH',
        body: JSON.stringify({
          httpPort: this.configForm.httpPort,
          tcpPort: this.configForm.tcpPort
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
