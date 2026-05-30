/**
 * AutoDial Background Script v2.1
 * 负责：复制剪贴板 + HTTP拨号 + 跨frame通信 + 悬浮窗/短信控制
 */
console.log('[AutoDial BG] v2.0 已加载');

// 存储每个tab最新的客户手机号
const tabPhones = {};

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  const tabId = sender?.tab?.id;

  // ── 子iframe检测到客户手机号 → 通知顶层页面浮动按钮 ──────────────
  if (msg.type === 'phoneDetected') {
    if (tabId) {
      tabPhones[tabId] = msg.phone;
      // 发给顶层页面 (frameId: 0)
      chrome.tabs.sendMessage(tabId, { type: 'updatePhone', phone: msg.phone }, { frameId: 0 })
        .catch(() => {}); // 忽略错误（页面可能未准备好）
    }
    return;
  }

  // ── 打开PC端主界面 ────────────────────────────────────────────────
  if (msg.type === 'openDesktop') {
    fetch('http://127.0.0.1:35432/open', { signal: AbortSignal.timeout(3000) })
      .then(res => res.json())
      .then(data => {
        console.log('[AutoDial BG] ✓ 打开主界面:', data);
        sendResponse({ success: data.success });
      })
      .catch(err => {
        console.error('[AutoDial BG] ✗ 打开主界面失败:', err.message);
        sendResponse({ success: false });
      });
    return true; // 异步 sendResponse
  }

  // ── 切换悬浮横条显示/隐藏 ────────────────────────────────────────
  if (msg.type === 'toggleFloatbar') {
    fetch('http://127.0.0.1:35432/toggle-floatbar', { signal: AbortSignal.timeout(3000) })
      .then(res => res.json())
      .then(data => {
        console.log('[AutoDial BG] ✓ 切换悬浮窗:', data);
        sendResponse({ success: data.success, visible: data.visible });
      })
      .catch(err => {
        console.error('[AutoDial BG] ✗ 切换悬浮窗失败:', err.message);
        sendResponse({ success: false });
      });
    return true;
  }

  // ── 发送短信（打开短信窗口） ─────────────────────────────────────
  if (msg.type === 'sendSms') {
    const phone = msg.phone;
    console.log('[AutoDial BG] 发短信:', phone);
    fetch(`http://127.0.0.1:35432/sms?number=${encodeURIComponent(phone)}`, { signal: AbortSignal.timeout(3000) })
      .then(res => res.json())
      .then(data => {
        console.log('[AutoDial BG] ✓ 打开短信窗口:', data);
        sendResponse({ success: data.success });
      })
      .catch(err => {
        console.error('[AutoDial BG] ✗ 打开短信窗口失败:', err.message);
        sendResponse({ success: false });
      });
    return true;
  }

  // ── 挂断电话 ──────────────────────────────────────────────────
  if (msg.type === 'hangup') {
    fetch('http://127.0.0.1:35432/hangup', { signal: AbortSignal.timeout(3000) })
      .then(res => res.json())
      .then(data => {
        console.log('[AutoDial BG] ✓ 挂断:', data);
        sendResponse({ success: data.success, error: data.error });
      })
      .catch(err => {
        console.error('[AutoDial BG] ✗ 挂断失败:', err.message);
        sendResponse({ success: false, error: err.message });
      });
    return true;
  }

  // ── 拨号请求 ──────────────────────────────────────────────────────
  if (msg.type === 'dial') {
    const phone = msg.phone;
    console.log('[AutoDial BG] 拨号:', phone);

    fetch(`http://127.0.0.1:35432/dial?number=${encodeURIComponent(phone)}`, { signal: AbortSignal.timeout(5000) })
      .then(res => {
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
      })
      .then(data => {
        // E-2修复: PC 在手机不在线时返回 success:false 或 success:true+waking:true，
        // 不应一律当作 ok:true 显示"已拨出"。区分 dialed/waking/error 三态。
        const dialed = !!data.success && !data.waking;
        const waking = !!data.waking;
        if (dialed) {
          console.log('[AutoDial BG] ✓ 拨号已发出:', data);
        } else if (waking) {
          console.log('[AutoDial BG] ⏳ 手机不在线，已触发唤醒:', data);
        } else {
          console.warn('[AutoDial BG] ✗ 拨号失败:', data);
        }
        if (tabId) {
          chrome.tabs.sendMessage(tabId, {
            type: 'dialResult',
            ok: dialed,
            waking,
            err: dialed ? null : (data.error || (waking ? '唤醒中...' : '拨号失败'))
          }, { frameId: 0 }).catch(() => {});
        }
      })
      .catch(err => {
        console.error('[AutoDial BG] ✗ 拨号失败:', err.message);
        if (tabId) {
          chrome.tabs.sendMessage(tabId, { type: 'dialResult', ok: false, err: err.message }, { frameId: 0 }).catch(() => {});
        }
      });

    return true;
  }
});

// tab关闭时清理缓存
chrome.tabs.onRemoved.addListener((tabId) => {
  delete tabPhones[tabId];
});
