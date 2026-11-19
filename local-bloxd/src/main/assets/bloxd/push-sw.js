// Shows bloxd.io's web push notifications (update logs from UpdateLogPushNotifier, friends' invites from
// SocialInvitePushNotifier) and opens the site when one is clicked.
// Served from the site root, outside the webpack build, so its URL never changes.

const defaultUrl = "/?utm_source=webpush&utm_medium=notification"

// Only a path on this site, so a push can never send the player elsewhere
function getClickUrl(url) {
	if (typeof url === "string" && url.startsWith("/") && !url.startsWith("//")) {
		return new URL(url, self.location.origin).href
	}
	return new URL(defaultUrl, self.location.origin).href
}

self.addEventListener("install", () => {
	self.skipWaiting()
})

self.addEventListener("activate", (event) => {
	event.waitUntil(self.clients.claim())
})

self.addEventListener("push", (event) => {
	if (!event.data) {
		return
	}
	let payload
	try {
		payload = event.data.json()
	} catch (e) {
		console.error("Ignored a push that wasn't JSON:", e)
		return
	}
	event.waitUntil(
		self.registration.showNotification(payload.title, {
			body: payload.body,
			icon: "/android-chrome-192x192.png",
			// Update logs send none, and replace each other
			tag: typeof payload.tag === "string" ? payload.tag : "updateLog",
			data: { url: getClickUrl(payload.url), inviteId: typeof payload.inviteId === "string" ? payload.inviteId : null },
		}),
	)
})

self.addEventListener("notificationclick", (event) => {
	event.notification.close()
	const url = event.notification.data?.url ?? getClickUrl(null)
	const inviteId = event.notification.data?.inviteId ?? null
	event.waitUntil(
		self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((windowClients) => {
			const openClient = windowClients.find((client) => {
				return new URL(client.url).origin === self.location.origin
			})
			if (openClient) {
				// Accepted where the player already is, rather than reloading a page that may be in a game
				if (inviteId !== null) {
					openClient.postMessage({ type: "bloxdAcceptInvite", inviteId })
				}
				return openClient.focus()
			}
			return self.clients.openWindow(url)
		}),
	)
})
