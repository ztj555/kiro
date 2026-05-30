/**
 * AutoDial 浏览器插件 - Popup v2.1
 * 现代极简风格，与 PC 端 UI 保持一致
 */

document.addEventListener('DOMContentLoaded', () => {
  const statusLine = document.getElementById('statusLine');
  const statusCard = document.getElementById('statusCard');
  const statusDot  = document.getElementById('statusDot');
  const statusText = document.getElementById('statusText');
  const localIP    = document.getElementById('localIP');
  const pinCode    = document.getElementById('pinCode');
  const phoneStatus = document.getElementById('phoneStatus');
  const phoneList  = document.getElementById('phoneList');
  const refreshBtn = document.getElementById('refreshBtn');
  const openDesktop = document.getElementById('openDesktop');

  function setConnecting() {
    statusLine.className = 'status-line connecting';
    statusCard.className = 'status-card connecting';
    statusDot.className  = 'status-dot connecting';
    statusText.className = 'status-text';
    statusText.textContent = '正在连接 PC 端...';
  }

  function setConnected(data) {
    statusLine.className = 'status-line connected';
    statusCard.className = 'status-card connected';
    statusDot.className  = 'status-dot connected';
    statusText.className = 'status-text connected';
    statusText.textContent = '✓ PC 端已连接';

    localIP.textContent = data.ip || '--';
    pinCode.textContent = data.pin || '----';

    const count = data.phoneCount || 0;
    const phones = data.phones || [];

    if (count > 0) {
      phoneStatus.textContent = '✓ ' + count + ' 部手机在线';
      phoneStatus.className = 'info-value ok';

      // 渲染手机列表
      phoneList.innerHTML = '';
      phones.forEach(p => {
        const item = document.createElement('div');
        item.className = 'phone-item';

        const dot = document.createElement('div');
        dot.className = 'phone-item-dot';

        const name = document.createElement('div');
        name.className = 'phone-item-name';
        name.textContent = p.note || p.name || '手机';

        const badge = document.createElement('div');
        badge.className = 'phone-item-badge';
        const connType = p.connectionType || (p.isCloud ? 'cloud' : 'lan');
        if (connType === 'lan') {
          badge.className += ' badge-lan';
          badge.textContent = 'LAN';
        } else if (connType === 'cloud') {
          badge.className += ' badge-cloud';
          badge.textContent = '云端';
        } else {
          badge.className += ' badge-cloud';
          badge.textContent = '双通道';
        }

        item.appendChild(dot);
        item.appendChild(name);
        item.appendChild(badge);
        phoneList.appendChild(item);
      });
    } else {
      phoneStatus.textContent = '✗ 手机未连接';
      phoneStatus.className = 'info-value fail';
      phoneList.innerHTML = '';
    }
  }

  function setError() {
    statusLine.className = 'status-line';
    statusCard.className = 'status-card error';
    statusDot.className  = 'status-dot';
    statusText.className = 'status-text error';
    statusText.textContent = '✗ PC 端未运行';
    localIP.textContent = '--';
    pinCode.textContent = '--';
    phoneStatus.textContent = '--';
    phoneStatus.className = 'info-value normal';
    phoneList.innerHTML = '';
  }

  function checkStatus() {
    fetch('http://127.0.0.1:35432/', { signal: AbortSignal.timeout(3000) })
      .then(r => r.json())
      .then(data => setConnected(data))
      .catch(() => setError());
  }

  // 刷新按钮
  refreshBtn.addEventListener('click', () => {
    refreshBtn.classList.add('spinning');
    setConnecting();
    checkStatus();
    setTimeout(() => refreshBtn.classList.remove('spinning'), 1000);
  });

  // 打开主界面
  openDesktop.addEventListener('click', () => {
    fetch('http://127.0.0.1:35432/open').catch(() => {});
  });

  // ==================== 快速拨号 ====================
  const quickDialInput = document.getElementById('quickDialInput');
  const quickDialBtn   = document.getElementById('quickDialBtn');
  const quickHangupBtn = document.getElementById('quickHangupBtn');

  // 从剪贴板自动填入号码
  function tryFillFromClipboard() {
    navigator.clipboard.readText().then(text => {
      const m = text.match(/1[3-9]\d{9}/);
      if (m && !quickDialInput.value) {
        quickDialInput.value = m[0];
        quickDialInput.style.borderColor = 'rgba(79,142,247,0.5)';
      }
    }).catch(() => {
      // Bug18修复: 剪贴板读取失败（popup未聚焦或权限不足）时，
      // 显示占位提示，引导用户手动输入
      if (!quickDialInput.value) {
        quickDialInput.placeholder = '输入号码（剪贴板需聚焦后读取）';
      }
    });
  }
  tryFillFromClipboard();

  function showQuickFeedback(btn, text, color, ms) {
    const orig = btn.textContent;
    const origBg = btn.style.background;
    btn.textContent = text;
    btn.style.background = color;
    setTimeout(() => {
      btn.textContent = orig;
      btn.style.background = origBg;
    }, ms || 1500);
  }

  quickDialBtn.addEventListener('click', () => {
    const number = quickDialInput.value.trim().replace(/[\s\-]/g, '');
    if (!number) {
      quickDialInput.focus();
      quickDialInput.style.borderColor = 'rgba(239,68,68,0.6)';
      setTimeout(() => { quickDialInput.style.borderColor = 'rgba(255,255,255,0.08)'; }, 1000);
      return;
    }
    fetch(`http://127.0.0.1:35432/dial?number=${encodeURIComponent(number)}`)
      .then(r => r.json())
      .then(data => {
        // E-2修复: 区分 dialed / waking / 失败
        const dialed = !!data.success && !data.waking;
        const waking = !!data.waking;
        if (dialed) {
          showQuickFeedback(quickDialBtn, '✓ 已拨出', 'linear-gradient(135deg,#16A34A,#15803D)', 2000);
        } else if (waking) {
          showQuickFeedback(quickDialBtn, '⏳ 唤醒中...', 'linear-gradient(135deg,#C9A84C,#8B6914)', 2000);
        } else {
          showQuickFeedback(quickDialBtn, '✗ ' + (data.error || '失败'), 'linear-gradient(135deg,#DC2626,#B91C1C)', 2000);
        }
      })
      .catch(() => {
        showQuickFeedback(quickDialBtn, '✗ PC端未运行', 'linear-gradient(135deg,#DC2626,#B91C1C)', 2000);
      });
  });

  quickHangupBtn.addEventListener('click', () => {
    fetch('http://127.0.0.1:35432/hangup')
      .then(r => r.json())
      .then(data => {
        showQuickFeedback(quickHangupBtn, data.success ? '✓ 已挂断' : '✗ 失败', null, 1500);
      })
      .catch(() => {
        showQuickFeedback(quickHangupBtn, '✗ 未运行', null, 1500);
      });
  });

  // Enter 键快速拨号
  quickDialInput.addEventListener('keydown', e => {
    if (e.key === 'Enter') quickDialBtn.click();
  });

  // 初始检查
  setConnecting();
  checkStatus();

  // 每 3 秒刷新
  setInterval(checkStatus, 3000);
});
