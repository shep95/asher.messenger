# Asher design system

Source of truth: `brands/asher/design/tokens.json`. Assets: `brands/asher/design/earth.webp`
(wallpaper), `brands/asher/design/sounds/asher_notify.wav`, `asher_sent.wav`.

The look is the wallpaper: Earth from low orbit. A black void, a thin
atmosphere lit from one side, cloud white, one small craft. Every surface in
the app is a shade of that void; the only saturated colour is the
atmosphere blue, and it is spent on the things that matter: your own words,
the send button, focus, presence.

## Rules

1. **Void, not grey.** Backgrounds are `#05070B` to `#16202D`. No neutral
   greys; every tint carries a little blue.
2. **One accent.** Atmosphere `#3D8FCF`. Outgoing bubbles, primary buttons,
   focus rings, read receipts, online presence. Nothing else is blue.
3. **Rim light for identity.** Avatars are circles with a 1.5 px ring that is
   lit on one side and fades to nothing, like the planet's limb.
4. **Horizon bubbles.** 18 px corners, 4 px on the tail corner; grouped
   messages from one sender use 6 px on the shared side and a 2 px gap.
   Outgoing bubbles carry a slight gradient, incoming are flat with a 1 px
   border. Timestamps sit inside the bubble at 11 px.
5. **Breathing room.** 4 pt grid, 72 px list rows, 16 px gutters, bubbles at
   most 72 % wide. Density is for dashboards; this is for reading people.
6. **Motion is orbital.** Ease-out `cubic-bezier(0.2,0.8,0.2,1)`, 160/240/400
   ms. Sent messages rise 8 px and fade in. Received messages scale from
   0.96. Typing dots rise 3 px and brighten in turn. The wallpaper drifts
   1.5 % over a minute. Everything collapses to crossfades under
   reduce-motion.
7. **The scene indicator** under the conversation title says how you are
   connected right now: *Orbit* (internet), *Mesh · 2 hops* (radio),
   *Carrying* (queued for a relay), *Out of range*. It is the one place the
   transport shows itself; the conversation otherwise looks the same on any
   link.
8. **Sound and touch.** One soft two-tone chime for arrivals, a short tick
   for sends, a 40/60/40 ms vibration. Never more.
9. **Type.** Inter for UI and Space Grotesk for display on Desktop and web;
   the system font on phones with the same scale and tracking.
10. **Name.** The app is **Asher**. Tagline: *Talk from anywhere. Even nowhere.*

## Where it lands

| Platform | Files (see `docs/patches.md`) |
|---|---|
| Android | `core-ui` theme colours and Compose theme, `colors.xml`/`themes.xml`, message bubble dimens/drawables, avatar view, typing indicator, notification channel sound and vibration, built-in wallpaper |
| iOS | `Theme.swift`/`UIColor+OWS`, `ConversationStyle` bubble metrics, avatar views, typing indicator, notification sound, built-in wallpaper |
| Desktop | `stylesheets/_asher.scss` overriding the CSS custom properties and component rules, wallpaper on the app frame, fonts, notification sound |
| Web | `web/landing/` |
