/**
 * AutoDial PC端 - 主题引擎
 * 负责 CSS 变量注入、主题应用、跨窗口同步
 * 支持 dark / dusk / dawn / twilight / warm / mist / light 七种亮度模式
 */

(function() {
  'use strict';

  let currentThemeId = DEFAULT_THEME || 'dark-gold';
  let currentMode = DEFAULT_MODE || 'dark';

  // --- 工具函数 ---
  function hexToRgb(hex) {
    if (!hex || typeof hex !== 'string') return null;
    hex = hex.replace('#', '');
    if (hex.length === 3) hex = hex.split('').map(function(c) { return c + c; }).join('');
    if (hex.length !== 6) return null;
    return {
      r: parseInt(hex.substring(0, 2), 16),
      g: parseInt(hex.substring(2, 4), 16),
      b: parseInt(hex.substring(4, 6), 16)
    };
  }

  function getLuminance(hex) {
    var rgb = hexToRgb(hex);
    if (!rgb) return 0;
    var r = rgb.r / 255, g = rgb.g / 255, b = rgb.b / 255;
    r = r <= 0.03928 ? r / 12.92 : Math.pow((r + 0.055) / 1.055, 2.4);
    g = g <= 0.03928 ? g / 12.92 : Math.pow((g + 0.055) / 1.055, 2.4);
    b = b <= 0.03928 ? b / 12.92 : Math.pow((b + 0.055) / 1.055, 2.4);
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  }

  // 查找主题数据
  function findTheme(id) {
    return THEME_DATA.find(function(t) { return t.id === id; }) || THEME_DATA[0];
  }

  // 将对象写入 document.documentElement.style
  function setCSSVars(vars) {
    var root = document.documentElement;
    for (var key in vars) {
      if (!vars.hasOwnProperty(key)) continue;
      var cssKey = '--' + key.replace(/([A-Z])/g, '-$1').toLowerCase();
      root.style.setProperty(cssKey, vars[key]);
    }
  }

  // CSS变量名映射：JS驼峰 -> CSS连字符
  var COLOR_MAP = {
    gold: 'gold', goldLight: 'gold-light', goldDark: 'gold-dark',
    bg: 'bg', bg2: 'bg2', bg3: 'bg3',
    text: 'text', text2: 'text2',
    green: 'green', red: 'red',
    floatbarBg: 'floatbar-bg', floatbarBorder: 'floatbar-border', floatbarBlur: 'floatbar-blur'
  };

  // 根据背景明暗自动推导 blue / surface / border 系列色值
  function deriveAdaptiveColors(colors) {
    var root = document.documentElement;
    var bgColor = colors.bg || '#111318';
    var isLight = getLuminance(bgColor) > 0.35;

    // 深色底 → 亮蓝；浅色底 → 深蓝（保证对比度）
    var blueHex   = isLight ? '#2563EB' : '#4F8EF7';
    var blueDark  = isLight ? '#1D4ED8' : '#2563EB';
    var blueR     = isLight ? 37  : 79;
    var blueG     = isLight ? 99  : 142;
    var blueB     = isLight ? 235 : 247;

    root.style.setProperty('--blue', blueHex);
    root.style.setProperty('--blue-dark', blueDark);
    root.style.setProperty('--blue-glow', 'rgba(' + blueR + ',' + blueG + ',' + blueB + ',' + (isLight ? '0.18)' : '0.3)'));
    root.style.setProperty('--gradient-blue', 'linear-gradient(135deg, ' + blueHex + ', ' + blueDark + ')');

    // 蓝色透明渐变：覆盖 rgba(79,142,247,0.xx) 硬编码值的所有使用场景
    var opacities = [5, 7, 8, 10, 12, 15, 20, 22, 25, 30, 40, 45, 50, 60];
    for (var i = 0; i < opacities.length; i++) {
      var op = opacities[i];
      var pad = op < 10 ? '0' + op : '' + op;
      root.style.setProperty('--blue-' + pad, 'rgba(' + blueR + ',' + blueG + ',' + blueB + ',' + (op / 100) + ')');
    }

    // 表面色：深色模式深灰，浅色模式用主题的 bg2/bg3
    root.style.setProperty('--surface', colors.bg2 || (isLight ? '#FFFFFF' : '#161920'));
    root.style.setProperty('--surface2', colors.bg3 || (isLight ? '#F0F0F0' : '#1E2128'));

    // 边框：深色模式白色半透，浅色模式黑色半透
    root.style.setProperty('--border', isLight ? 'rgba(0,0,0,0.08)' : 'rgba(255,255,255,0.08)');
    root.style.setProperty('--border-active', 'rgba(' + blueR + ',' + blueG + ',' + blueB + ',' + (isLight ? '0.3)' : '0.4)'));
  }

  // 应用主题（主入口）
  function applyTheme(themeId, mode) {
    currentThemeId = themeId || currentThemeId;
    currentMode = mode || currentMode;

    var theme = findTheme(currentThemeId);
    var colors = theme[currentMode] || theme.dark;

    // 注入主题颜色变量
    var colorVars = {};
    for (var key in COLOR_MAP) {
      if (!COLOR_MAP.hasOwnProperty(key)) continue;
      if (colors[key] !== undefined) {
        colorVars[key] = colors[key];
      }
    }
    setCSSVars(colorVars);

    // 注入风格变量
    if (theme.style) {
      setCSSVars(theme.style);
    }

    // 发光文字效果
    if (theme.style && theme.style.glowText && theme.style.glowText !== 'none') {
      document.documentElement.style.setProperty('--glow-text', theme.style.glowText);
    } else {
      try { document.documentElement.style.removeProperty('--glow-text'); } catch(_) {}
    }

    // 自动推导 adaptive 色值（blue/surface/border 等）
    deriveAdaptiveColors(colors);

    // 通知主进程更新窗口背景色
    try {
      window.api.send('update-bg-color', colors.bg);
    } catch(e) {}
  }

  // 初始化：从主进程拉取当前设置并应用
  function initTheme() {
    // 先应用默认主题，避免白闪
    applyTheme(DEFAULT_THEME, DEFAULT_MODE);

    // 然后尝试从主进程获取真实设置
    if (typeof window !== 'undefined' && window.api) {
      window.api.invoke('get-theme-setting').then(setting => {
        if (setting) {
          applyTheme(setting.theme, setting.mode);
        }
      }).catch(() => {});

      // 监听其他窗口触发的主题变更
      window.api.on('theme-changed', (data) => {
        applyTheme(data.theme || data.id, data.mode);
      });
    }
  }

  // 获取当前主题信息
  function getCurrentThemeInfo() {
    return {
      id: currentThemeId,
      mode: currentMode,
      theme: findTheme(currentThemeId)
    };
  }

  // 暴露到全局
  window.ThemeEngine = {
    applyTheme,
    initTheme,
    getCurrentThemeInfo,
    findTheme
  };
})();
