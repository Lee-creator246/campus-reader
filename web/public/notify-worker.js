self.addEventListener("push", event => {
  const icon = new URL("icon.svg", self.registration.scope).href;
  event.waitUntil(self.registration.showNotification("北邮人信息", {
    body: "点按打开阅读页，查看公开通知。",
    icon,
    tag: "campus-reader-public",
  }));
});

self.addEventListener("notificationclick", event => {
  event.notification.close();
  event.waitUntil(openPublicNotices());
});

async function openPublicNotices() {
  const target = new URL("./#campus/all", self.registration.scope);
  const windows = await self.clients.matchAll({type: "window", includeUncontrolled: true});
  for (const client of windows) {
    const current = new URL(client.url);
    if (current.origin !== target.origin ||
        ![target.pathname, target.pathname + "index.html"].includes(current.pathname)) continue;
    try {
      const navigated = await client.navigate(target.href);
      if (navigated) { await navigated.focus(); return; }
    } catch { /* The window may have closed while the notification was open. */ }
  }
  await self.clients.openWindow(target.href);
}
