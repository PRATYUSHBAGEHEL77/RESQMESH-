# RESQMesh — Offline SOS field-test candidate (0.4.0)

This Android prototype exchanges SOS envelopes between nearby Android devices using Google Play services Nearby Connections. It is not certified or validated for real emergency response. Never rely on it as the only way to summon help.

## Changes in 0.4.0 (reliability, security and background hardening)

- **Background operation:** the mesh now runs in a foreground service (`MeshService`) with a persistent notification and a partial wake lock, so it keeps working with the screen off or the app in the background. A card in the app shows battery-saver state and asks for the battery-optimisation exemption. Incoming SOS messages raise a high-priority notification.
- **Reconnection:** after a disconnect (and every minute with no peers) discovery and advertising restart, so phones find each other again.
- **Delivery tracking:** every SOS you send has a status that survives restarts: QUEUED, SENT, ACKNOWLEDGED (with the names of the phones that acknowledged) or EXPIRED. Unacknowledged SOS messages are retried every 20 s while peers are connected, and expire after 24 h.
- **Multi-hop custody:** a relay phone keeps its copy until a phone other than the one it received it from acknowledges it. ACKs are flooded back, so the origin sees receipts from the relay and from the final phone.
- **GPS:** each SOS is labelled LIVE or CACHED with its fix age, accuracy and provider. A live fix is tried for 15 s; if it fails, a last-known fix up to 30 min old is used and flagged as cached; otherwise the SOS goes out clearly marked as having no coordinates. Receivers see a warning for cached, old or imprecise fixes.
- **Security:** every SOS and ACK is signed (ECDSA P-256, key held in the Android Keystore). Tampered messages are rejected; unsigned messages are shown with a warning and never relayed; a name that suddenly uses a different key is flagged as possible impersonation (trust on first use). Already-seen message IDs are stored on disk, so replays after a restart are ignored. Peers you have verified once reconnect automatically.
- **Offline direction aid:** each located SOS has COPY and DIRECTION buttons. DIRECTION shows distance and compass bearing from your last known position, with no map or internet.
- **Event log:** events carry timestamps and are saved across restarts; warnings are highlighted. "Clear saved SOS, history and trusted peers" removes stored data.

## Build without Android Studio
Use the included GitHub Actions workflow. Upload the repository files to GitHub and wait for the workflow to complete; download the `RESQMesh-debug-APK` artifact.

## Two-phone test procedure
1. Install the same build on both phones. The app can now stay in the background, but run the first test with the screen on.
2. Grant Nearby/Bluetooth permissions. Turn on Bluetooth and Wi-Fi on both phones. Turn on device Location for GPS tests.
3. Use distinct names (the app generates a distinct default name on a fresh install).
4. Tap **Start mesh** on both. Confirm each side lists a connected peer.
5. On phone A tap **Broadcast SOS with GPS** outdoors or near a window and wait for a real GPS fix.
6. On phone B verify an `SOS RECEIVED` event, coordinates and `OPEN LOCATION IN MAPS`. On phone A verify `SOS RECEIPT CONFIRMED`.
7. Repeat with internet/mobile data disabled. GPS coordinates can be obtained offline, but mapping apps only show maps offline if map data is already downloaded.
8. Test with GPS disabled, permission denied, a disconnected peer, duplicate delivery, app restart and a third phone acting as a relay. Record failures rather than assuming success.

## Known limitations — not production-ready

- Not compiled or tested on physical devices by the author of these changes. A green cloud build only proves compilation; every item above must be verified on real phones.
- No end-to-end encryption: relay phones can read message text and location. Signatures prove who sent a message and that it was not altered, not that it is private.
- Identity is "trust on first use" by device name. There are no contacts or accounts.
- No built-in offline map. "OPEN MAP" needs a map app with a pre-downloaded area; COPY and DIRECTION work without one. Bundling offline tiles (for example MapLibre or osmdroid with a prepared tile archive) is still to do.
- Nearby Connections support and radio behaviour vary by Android version, vendor and Google Play services availability. Some phone makers kill background apps despite the foreground service.
- Multi-hop forwarding is best-effort and must be physically verified; it is not a guaranteed mesh network.
- A peer ACK means the RESQMesh app received the SOS, not that responders or emergency services received it.
- GPS can be unavailable or inaccurate indoors; never treat coordinates as verified if they are old or inaccurate.

Do not advertise or deploy this build as emergency-ready. Real deployment requires independent engineering/security review, privacy review, usability testing, and field reliability testing with documented pass criteria.

## Field test checklist for 0.4.0

1. Delivery: send an SOS, confirm RECEIVED on phone B and ACKNOWLEDGED on A. Turn B's Bluetooth off before sending and confirm A shows QUEUED, then turn it back on and confirm the retry and ACK.
2. Persistence: send with no peer, force-close the app, reopen, connect a peer; the SOS must send.
3. GPS: test live fix, cached fix, location switched off and permission denied; check the LIVE/CACHED label and warnings on the receiver.
4. Security: edit a message in transit (needs a test build), replay an old message, and reinstall a phone to see the KEY CHANGED warning.
5. Multi-hop: place A and C out of range of each other with B between; confirm C receives and A shows ACKs from both B and C.
6. Background: screen off for 10 min, battery saver on, kill the app from recents, toggle airplane mode on and off; the mesh must recover.
7. Offline aid: with data off, use COPY and DIRECTION on a received SOS.
