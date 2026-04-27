// ============================================================
// InstantIoT — Internationalization (EN / FR)
// English by default — used by Alpine.js via t(key, lang)
// ============================================================

const I18N = {

  en: {
    'login.subtitle':      'Server administration panel',
    'login.username':      'Username',
    'login.password':      'Password',
    'login.submit':        'Sign in',
    'login.error':         'Invalid credentials',
    'login.adminOnly':     'This panel is restricted to the server administrator',

    'setup.subtitle':      'First launch — Secure your admin account',
    'setup.current':       'Current password',
    'setup.new':           'New password (8 chars min)',
    'setup.confirm':       'Confirm new password',
    'setup.submit':        'Change password',
    'setup.mismatch':      'Passwords do not match',
    'setup.tooShort':      'Minimum 8 characters',
    'setup.error':         'Error while changing password',

    'nav.overview':        'Overview',
    'nav.devices':         'Registered devices',
    'nav.logout':          'Sign out',

    'dashboard.title':     'Server overview',
    'dashboard.serverStatus': 'Server status',
    'dashboard.autoRefresh':  'Auto-refresh every 10s',

    'stats.accounts':      'Accounts',
    'stats.projects':      'Projects',
    'stats.devices':       'Devices',
    'stats.online':        'Devices online',
    'stats.appSessions':   'App sessions',
    'stats.tcpConnections': 'TCP connections',

    'config.title':        'Network ports',
    'config.httpPort':     'HTTP / WebSocket port',
    'config.tcpPort':      'TCP port (devices)',
    'config.save':         'Save',
    'config.restart':      'Restart server',
    'config.restartNote':  'Server restart required to apply port changes.',
    'config.saved':        'Ports saved — restart the server to apply',
    'config.saveError':    'Error while saving',

    'history.title':               'How long to keep my data',
    'history.intro':               'Older data is automatically summarized and eventually removed to keep the server fast.',
    'history.retentionRaw':        'Keep every measurement for (days)',
    'history.retentionRawHelp':    'Full-detail sensor readings — kept this many days, then replaced by summaries.',
    'history.retentionOpaque':     'Keep text events for (days)',
    'history.retentionOpaqueHelp': 'Non-numeric events such as messages or logs.',
    'history.throttle':            'Save a measurement at most every (seconds)',
    'history.throttleHelp':        'Limits disk usage when a sensor sends data very fast. Your app still sees everything in real time. Use 0 to save every single reading.',
    'history.retentionMin':        'Keep minute-by-minute summaries for (days)',
    'history.retentionMinHelp':    'Once detailed readings are gone, one summary point per minute is kept for this long.',
    'history.retentionHour':       'Keep hourly summaries for (days)',
    'history.retentionHourHelp':   'One summary point per hour — useful to view weeks or months at a glance.',
    'history.retentionDay':        'Keep daily summaries for (days, -1 = forever)',
    'history.retentionDayHelp':    'One summary point per day. Enter -1 to keep them forever.',
    'history.downsampleInterval':  'Refresh summaries every (minutes)',
    'history.downsampleIntervalHelp': 'How often the server rebuilds summaries and cleans up old data.',
    'history.save':                'Save',
    'history.reset':               'Reset',
    'history.saved':               'Saved — applied on the next cycle',
    'history.saveError':           'Could not save',
    'history.note':                'No restart needed — your changes apply automatically.',

    'info.title':          'System information',
    'info.localIp':        'Local IP',
    'info.uptime':         'Uptime',
    'info.database':       'Database',

    'devices.title':       'Devices registered on this server',
    'devices.name':        'Name',
    'devices.projectId':   'Project ID',
    'devices.status':      'Status',
    'devices.lastSeen':    'Last seen',
    'devices.empty':       'No devices registered on this server',
    'devices.online':      'Online',
    'devices.offline':     'Offline',

    'licence.subtitle':    'Enter your licence key to activate this server',
    'licence.placeholder': 'Paste your licence key here...',
    'licence.submit':      'Activate',
    'licence.empty':       'Please enter a licence key',
    'licence.invalid':     'Invalid licence key',
    'licence.error':       'Error activating licence',

    'restart.title':       'Restart server?',
    'restart.message':     'The server will shut down. If a process manager (systemd, etc.) is configured, it will restart automatically. Otherwise you will need to start it manually.',
    'restart.confirm':     'Restart now',
    'restart.cancel':      'Cancel',
    'restart.disconnected': 'Server is restarting...',

    'uptime.days': 'd', 'uptime.hours': 'h', 'uptime.minutes': 'min', 'uptime.seconds': 's',
  },

  fr: {
    'login.subtitle':      'Panneau d\'administration serveur',
    'login.username':      'Nom d\'utilisateur',
    'login.password':      'Mot de passe',
    'login.submit':        'Se connecter',
    'login.error':         'Identifiants incorrects',
    'login.adminOnly':     'Ce panneau est reserve a l\'administrateur du serveur',

    'setup.subtitle':      'Premier lancement — Securisez votre compte administrateur',
    'setup.current':       'Mot de passe actuel',
    'setup.new':           'Nouveau mot de passe (8 chars min)',
    'setup.confirm':       'Confirmer le nouveau mot de passe',
    'setup.submit':        'Changer le mot de passe',
    'setup.mismatch':      'Les mots de passe ne correspondent pas',
    'setup.tooShort':      'Minimum 8 caracteres',
    'setup.error':         'Erreur lors du changement',

    'nav.overview':        'Vue d\'ensemble',
    'nav.devices':         'Devices enregistres',
    'nav.logout':          'Deconnexion',

    'dashboard.title':     'Vue d\'ensemble du serveur',
    'dashboard.serverStatus': 'Etat du serveur',
    'dashboard.autoRefresh':  'Actualisation automatique toutes les 10s',

    'stats.accounts':      'Comptes',
    'stats.projects':      'Projets',
    'stats.devices':       'Devices',
    'stats.online':        'Devices en ligne',
    'stats.appSessions':   'Sessions app',
    'stats.tcpConnections': 'Connexions TCP',

    'config.title':        'Ports reseau',
    'config.httpPort':     'Port HTTP / WebSocket',
    'config.tcpPort':      'Port TCP (devices)',
    'config.save':         'Sauvegarder',
    'config.restart':      'Redemarrer le serveur',
    'config.restartNote':  'Redemarrage necessaire pour appliquer les changements de ports.',
    'config.saved':        'Ports sauvegardes — redemarrez le serveur pour appliquer',
    'config.saveError':    'Erreur lors de la sauvegarde',

    'history.title':               'Duree de conservation des donnees',
    'history.intro':               'Les anciennes donnees sont automatiquement resumees puis supprimees pour garder le serveur rapide.',
    'history.retentionRaw':        'Garder chaque mesure pendant (jours)',
    'history.retentionRawHelp':    'Mesures complètes des capteurs — conservees ce nombre de jours, puis remplacees par des resumes.',
    'history.retentionOpaque':     'Garder les evenements texte pendant (jours)',
    'history.retentionOpaqueHelp': 'Evenements non-numeriques comme les messages ou logs.',
    'history.throttle':            'Enregistrer une mesure au plus toutes les (secondes)',
    'history.throttleHelp':        'Limite l\'espace disque quand un capteur envoie tres vite. Votre app continue de tout voir en direct. Mettez 0 pour enregistrer chaque mesure.',
    'history.retentionMin':        'Garder les resumes minute par minute pendant (jours)',
    'history.retentionMinHelp':    'Une fois les mesures detaillees supprimees, un point par minute est conserve pendant cette duree.',
    'history.retentionHour':       'Garder les resumes heure par heure pendant (jours)',
    'history.retentionHourHelp':   'Un point par heure — pratique pour voir des semaines ou des mois d\'un coup d\'oeil.',
    'history.retentionDay':        'Garder les resumes jour par jour pendant (jours, -1 = pour toujours)',
    'history.retentionDayHelp':    'Un point par jour. Entrez -1 pour les garder indefiniment.',
    'history.downsampleInterval':  'Mettre a jour les resumes toutes les (minutes)',
    'history.downsampleIntervalHelp': 'Frequence a laquelle le serveur recalcule les resumes et nettoie les donnees anciennes.',
    'history.save':                'Sauvegarder',
    'history.reset':               'Reinitialiser',
    'history.saved':               'Sauvegarde — applique au prochain cycle',
    'history.saveError':           'Impossible de sauvegarder',
    'history.note':                'Aucun redemarrage necessaire — les changements sont pris en compte automatiquement.',

    'info.title':          'Informations systeme',
    'info.localIp':        'IP locale',
    'info.uptime':         'Uptime',
    'info.database':       'Base de donnees',

    'devices.title':       'Devices enregistres sur le serveur',
    'devices.name':        'Nom',
    'devices.projectId':   'Projet ID',
    'devices.status':      'Statut',
    'devices.lastSeen':    'Derniere connexion',
    'devices.empty':       'Aucun device enregistre sur ce serveur',
    'devices.online':      'En ligne',
    'devices.offline':     'Hors ligne',

    'licence.subtitle':    'Entrez votre cle de licence pour activer ce serveur',
    'licence.placeholder': 'Collez votre cle de licence ici...',
    'licence.submit':      'Activer',
    'licence.empty':       'Veuillez entrer une cle de licence',
    'licence.invalid':     'Cle de licence invalide',
    'licence.error':       'Erreur lors de l\'activation',

    'restart.title':       'Redemarrer le serveur ?',
    'restart.message':     'Le serveur va s\'arreter. Si un gestionnaire de processus (systemd, etc.) est configure, il redemarrera automatiquement. Sinon vous devrez le relancer manuellement.',
    'restart.confirm':     'Redemarrer maintenant',
    'restart.cancel':      'Annuler',
    'restart.disconnected': 'Le serveur redemarre...',

    'uptime.days': 'j', 'uptime.hours': 'h', 'uptime.minutes': 'min', 'uptime.seconds': 's',
  }
};

/**
 * Traduction — utilisee par Alpine.js via this.t(key)
 * @param {string} key - cle de traduction
 * @param {string} lang - langue courante ('en' ou 'fr')
 */
function t(key, lang) {
  lang = lang || 'en';
  return I18N[lang]?.[key] || I18N['en'][key] || key;
}
