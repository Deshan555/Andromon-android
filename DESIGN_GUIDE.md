# Andromon companion: design guide

How the Android companion app looks, behaves and reads, and how to build a new screen that fits. Everything here describes what the code does today (`Ui.kt`, `UiRows.kt`, `Screen.kt`, `MainActivity.kt`, `res/values/colors.xml`). Where the code and this guide disagree, fix one of them.

**Contents**

1. [Principles](#1-principles)
2. [Colour](#2-colour)
3. [Typography](#3-typography)
4. [Spacing and layout](#4-spacing-and-layout)
5. [Shape, depth and touch feedback](#5-shape-depth-and-touch-feedback)
6. [Icons](#6-icons)
7. [Motion](#7-motion)
8. [Navigation](#8-navigation)
9. [Components](#9-components)
10. [Data display](#10-data-display)
11. [Screen patterns](#11-screen-patterns)
12. [States: loading, empty, error, unavailable](#12-states-loading-empty-error-unavailable)
13. [Dialogs, toasts and confirmation](#13-dialogs-toasts-and-confirmation)
14. [Writing](#14-writing)
15. [Accessibility](#15-accessibility)
16. [Dark theme](#16-dark-theme)
17. [Building a screen, end to end](#17-building-a-screen-end-to-end)
18. [Review checklist](#18-review-checklist)
19. [Known gaps](#19-known-gaps)

---

## 1. Principles

1. **One headline per screen.** Each screen answers one question ("How full is my storage?", "Is the PC connected?"). The answer goes in the single lime gradient hero card, as a big figure or status. Everything else is supporting detail on off-white cards.
2. **Calm and flat.** A grey page, off-white cards, dark ink. No shadows, no borders, no decorative colour. Lime is the only brand colour and it marks the headline, not the chrome.
3. **Big numbers, light type.** Figures are large and set in Regular; headings are large and set in Light. Weight is never used for emphasis above SemiBold.
4. **Say what is true.** If a figure is an estimate, label it. If Android blocks something, say so in the row instead of hiding the row. If there is not enough data, say "Not enough data yet".
5. **Small and self-contained.** Plain framework views built in Kotlin. No AndroidX, no Compose, no Material library, no XML layouts. The APK stays small; a new dependency needs a strong reason.
6. **Nothing destructive without asking.** Deleting, uninstalling and force-stopping system apps always confirm first, with the count and the consequence.

---

## 2. Colour

### Brand palette

| Name | Hex | Role |
|---|---|---|
| Lime | `#E3F786` | Brand colour, soft accent fills |
| Green | `#9CD237` | Charts, status dots, gradient end tones |
| Ink | `#292A2C` | Text, icons, primary buttons, bottom bar |
| White | `#FFFFFF` | Text on ink, active nav item |

### Semantic tokens

Always use the resource, never a hex value in Kotlin. Night values come from `values-night/colors.xml` automatically.

| Token | Light | Dark | Use for |
|---|---|---|---|
| `bg` | `#E6E6E4` | `#151617` | Page background, window colour |
| `surface` | `#F5F5F3` | `#212224` | Solid card on the page |
| `glass` | white 65% | white 7% | Default card (`glassCard`), list pills |
| `glass_strong` | white 90% | white 13% | Icon chips, header buttons, note rows |
| `chip` | black 6% | white 10% | Small icon tile inside a card |
| `ink` | `#292A2C` | `#F2F2F0` | Primary text and icons |
| `ink_soft` | `#6C6D70` | `#A4A5A7` | Secondary text, captions, keys |
| `ink_faint` | `#A4A5A7` | `#66676A` | Disabled marks, chevrons, off-state tracks. Not for text. |
| `line` | `#DCDCD9` | `#2E2F32` | Dividers (rare), chart guides |
| `fab` / `on_fab` | `#292A2C` / `#FFFFFF` | `#E3F786` / `#1B1C1D` | Primary button fill and its label |
| `hero_start` → `hero` → `hero_end` | `#EAF9A8` → `#C6EC50` → `#A3D63B` | `#55691F` → `#43561A` → `#334414` | Hero card gradient, top-left to bottom-right |
| `good` | `#5F8F12` | `#B7E356` | Positive status text and icons |
| `good_bg` | `#E3F786` | `#3A4A18` | Fill behind a positive status |
| `green_dot` / `chart` | `#9CD237` | `#9CD237` | Status dots, chart lines and fills |
| `danger` | `#D9443F` | `#F08A86` | Destructive labels, failures |
| `marker` | `#E2504C` | `#F07A76` | Chart marker for a notable point |
| `yellow_line` | `#D9A514` | `#F2CB5C` | Warning line in charts |
| `ripple` | black 8% | white 16% | Touch feedback |
| `nav_bg` | `#292A2C` | `#3B3C40` | Bottom bar |
| `nav_active` | `#FFFFFF` | `#F2F2F0` | Square behind the open nav item |

Two lime tints do the work of every soft accent: strong (`#E3F786`, dark `#3A4A18`) and soft (`#EEF6CF`, dark `#2A3020`). The older pastel names (`salmon`, `peach`, `lavender`, `mint`, `sky`, `purple`, `blue_tile` and so on) all map onto these two. They still compile, but in new code use `good_bg` for the strong tint and `green_tile` for the soft one, and don't add new pastel names.

### Category colours

For breakdowns with several parts (storage donut, junk groups), in this order:

| Token | Light | Dark |
|---|---|---|
| `cat_blue` | `#292A2C` | `#F2F2F0` |
| `cat_salmon` | `#9CD237` | `#9CD237` |
| `cat_yellow` | `#C6EC50` | `#E3F786` |
| `cat_teal` | `#6FA51B` | `#6FA51B` |
| `cat_purple` | `#6C6D70` | `#B7B8BA` |
| `cat_green` | `#B7B8BA` | `#8A8B8E` |
| `cat_grey` | `#C9CACB` | `#55565A` |

The names are historical; the values are ink, greens and greys. Always pair a segment with a labelled legend row, since several of these are close in hue.

### Rules

- **One hero card per screen.** Never two gradients on one screen, and never a gradient on a button.
- **Text on the hero is `ink` only.** `ink_soft` on the gradient measures 3.8:1 in light and 2.5:1 in dark, so secondary text there fails. Use `ink` at a smaller size or Medium weight instead.
- **Red means danger or failure only.** Not "important", not "new".
- **Green means good or measured data.** Don't colour neutral values green.
- **Status is never colour alone.** Pair a dot or tint with a word ("Connected", "Good").

### Measured contrast

| Pair | Light | Dark | Verdict |
|---|---|---|---|
| `ink` on `bg` | 11.5:1 | 16.2:1 | Any text |
| `ink` on `surface` | 13.2:1 | 14.2:1 | Any text |
| `ink` on hero gradient | 8.4 to 10.6:1 | 5.5 to 9.5:1 | Any text |
| `ink_soft` on `surface` | 4.7:1 | 6.5:1 | Any text |
| `ink_soft` on `bg` | 4.1:1 | 7.4:1 | Light theme: 18sp and up only. Put small secondary text on a card. |
| `good` on `surface` | 3.6:1 | 10.7:1 | Light theme: icons and 18sp+ text only |
| `danger` on `surface` | 4.0:1 | 6.6:1 | Light theme: icons, 18sp+ text, or Medium 14sp+ labels |
| `ink_faint` on `surface` | 2.3:1 | 2.8:1 | Never text |
| `#9CD237` on `surface` | 1.7:1 | n/a | Fills and dots only |
| `on_fab` on `fab` | 14.4:1 | 14.6:1 | Any text |

---

## 3. Typography

**Poppins**, bundled in `res/font` (subset to Latin) and set app-wide through the theme's `android:fontFamily`. Four weights: Light 300, Regular 400, Medium 500, SemiBold 600. No italics: `ui.lightItalic` is an alias for Light.

Use `ui.text(sizeSp, color, face)`. It turns off font padding so spacing values are exact.

| Role | Size | Weight | Tracking | Colour | Where |
|---|---|---|---|---|---|
| Display heading | 38sp | Light | -0.03, line height 0.95 | `ink` | `ui.heading()` on intro screens |
| Hero figure | 40 to 52sp | Regular | -0.03 | `ink` | The headline number in the hero card |
| Page title | 28 to 30sp | Light | -0.02 | `ink` | `ui.topBar()`, `ui.pageTitle()` |
| Stat value | 24sp | Regular | 0 | `ink` | `ui.stat()`, `ui.numbers()` |
| Section heading | 22sp | Light | -0.02 | `ink` | `ui.section()` |
| Status title | 20sp | Regular | 0 | `ink` | Condition and result rows |
| Tile title | 17sp | Regular | 0 | `ink` | `ui.tile()` |
| Primary button | 16sp | Medium | 0 | `on_fab` | `ui.action()` |
| Row title | 15sp | Medium | 0 | `ink` | `cardHeader`, `UiRows` |
| Body, pill, value | 14sp | Regular (Medium for values and buttons) | 0 | `ink` | Notes, `keyValue`, `pill`, `listPill` |
| Caption | 13sp | Regular | 0 | `ink_soft` | Tile subtitles, notes, stat captions |
| Small | 12sp | Regular | 0 | `ink_soft` | Row explanations, number labels |

Rules:

- **12sp is the floor.** Nothing smaller, including chart labels.
- **Units are half size and softer.** Use `ui.withUnit("1.2", "GB")` and `ui.percent(92)`; never build these by hand.
- **Light is for 22sp and up.** Below that it gets too thin; use Regular.
- **SemiBold is rare.** Reserve `ui.bold` for a single word or figure that must stand out inside body text.
- **Sizes are in sp** and scale with the phone's font setting. Give titles `maxLines` plus an ellipsis; let explanations wrap (up to 4 lines in rows).
- **Multi-line text** below 16sp gets `setLineSpacing(dp(2), 1f)`.
- **Numbers that update live** should not make the layout jump: give the view a fixed or weighted width.

---

## 4. Spacing and layout

All measurements in dp through `ui.dp()`. The base unit is 2dp; most gaps are multiples of 4.

### Page

| Measure | Value |
|---|---|
| Side padding | 20dp, plus the system inset |
| Top padding | 16dp, plus the status bar inset |
| Bottom padding | Bottom bar (68dp) + 20dp + system inset, plus 80dp while a screen has a docked button; with the keyboard open, keyboard height + 24dp |

`MainActivity.applyInsets()` sets this padding on every screen's root view. A screen never adds its own page padding and never reads insets itself. The host also puts each screen inside its one `ScrollView`, so `build()` returns a plain column and never its own scroller.

### Vertical rhythm

| Between | Gap |
|---|---|
| Top bar and hero card | 24dp |
| Hero card and the next card | 12dp |
| A card and the next section heading | 26dp |
| Section heading and its card | 12dp |
| Sibling cards, tiles in a grid | 12dp |
| Title and its subtitle | 2 to 4dp |
| Caption and its value | 8 to 14dp |
| Icon and adjacent text | 12 to 14dp |

Set gaps with `ui.lp(top = …)` on the child being added, not with padding on the parent, so a hidden view takes its gap with it.

### Inside components

| Component | Padding |
|---|---|
| Hero card | 16 to 18dp |
| Card with free content | 16dp |
| Card holding setting rows | 6dp (the rows bring their own 10/12/8/12) |
| Tile | 16dp, 14dp on the trailing edge |
| Stat card | 14dp, 10dp trailing, 16dp bottom |
| Pill button | 18dp horizontal |
| List pill | 6dp, 14dp trailing |

### Sizes

| Element | Size |
|---|---|
| Primary button height | 56dp |
| Pill button height | 42dp |
| Header button | 42dp |
| Icon chip in a card header | 34dp |
| Row icon | 40dp circle, 19dp glyph |
| Nav item | Whole cell is the target; 56 × 32dp pill, 22dp glyph, 12sp label (at most 14dp) |
| Small pill | 36dp |
| Docked button | 56dp, 17dp above the bottom bar |
| Switch | 50 × 30dp |
| Chevron, trailing arrow | 20dp |

### Width

Screens are a single column. Two-up grids (tiles, stat cards) use equal weights with a 12dp gutter. Use `marginStart`/`paddingStart`, not left/right, so right-to-left layouts mirror. Test at 320dp wide and at the largest font size: a two-up grid whose text no longer fits should stack instead of truncating its figures.

---

## 5. Shape, depth and touch feedback

### Corner radii

| Radius | Used on |
|---|---|
| 50dp (full pill) | Buttons, pills, list pills, progress bars |
| 26dp | Cards, hero card |
| 24dp | Tiles, note rows, top corners of the bottom bar |
| 22dp | Stat cards |
| 20dp | Tappable row highlight |
| 16dp | Active nav item pill, switch track |
| Size ÷ 3 | Square icon buttons and icon tiles (42dp → 14dp) |
| Circle | Icon chips, row icons, dots, day chips |

Everything is rounded. Nested shapes get a smaller radius than their container.

### Depth

There are **no shadows and no borders**. Layers are separated by tone: page (`bg`), then card (`glass`), then chip (`glass_strong`). `ui.elevate()` exists but its shadow colour is transparent in dark theme; don't rely on it. Use a divider only inside long uniform lists, in `line`.

### Touch feedback

Every tappable surface is wrapped in `ui.pressable(bg)`, a ripple clipped to the shape. A tappable row with no fill uses `ui.pressable(ui.rounded(Color.TRANSPARENT, 20))`. If it responds to a tap, it ripples; if it ripples, it does something.

Disabled controls: `ui.setEnabled(view, false)` (40% opacity). Unavailable rows: `Row.available(false, ui, why = "…")` (50% opacity, with the reason in the second line).

---

## 6. Icons

- **Set:** [Lucide](https://lucide.dev) (ISC licence, text in `res/raw/lucide_isc.txt`). Credit the icon name in a comment at the top of each drawable.
- **Format:** vector drawable, 24 × 24 viewport, 2px stroke, round caps and joins, no fill, black stroke colour. Tint at use with `ui.icon(res, color)`.
- **Naming:** `ic_<thing>.xml`, named for what it shows (`ic_broom`), not where it is used.
- **Colour:** `ink` by default; `ink_faint` for chevrons; `good` or `danger` only when the icon carries that status.
- **One meaning per icon.** `ic_chevron_right` means "opens a page in the app". `ic_arrow_up_right` marks a dashboard tile. `ic_arrow_right` marks a tappable note. Don't swap them.

| Container | Function | Size | Glyph |
|---|---|---|---|
| Circle chip on a card | `ui.chipIcon()` | 34dp | 17dp |
| Circle in a setting row | `ui.roundIcon()` | 40dp | 19dp |
| Rounded square | `ui.iconTile()` | 28 to 40dp | 15 to 20dp |
| Scalloped badge | `ui.badge()` | 46 to 50dp | 40% of the size |
| Header button | `ui.circleButton()` | 42dp | 20dp |
| Bare icon | `ui.iconView()` | 18 to 26dp | same |

The scalloped badge is for one status mark per screen (battery condition, a result). Don't use it as a general icon holder.

---

## 7. Motion

Motion is sparse and only explains a change in a value.

| What | How |
|---|---|
| Gauge value changes (`Ring.fraction`) | 700ms, decelerate |
| Measuring in progress (`Ring.busy`) | A 90° arc turning once every 1.2s |
| Touch | System ripple |
| Switching screens | Instant swap, no transition |
| Start-up splash (`Splash.kt`) | The logo fades in (450ms), a lime-to-green bar fills over 5s, then the splash fades out (250ms). Timed by the clock, so it stays 5s with animations off; with reduce motion only the bar moves |

Rules for anything new: 150 to 300ms for small state changes, up to 700ms for a headline gauge, always decelerating. Animate from the value currently shown, not from zero, so a refresh doesn't replay the whole sweep. No looping or decorative animation except a busy indicator, and stop it as soon as the work ends. Check `Settings.Global.ANIMATOR_DURATION_SCALE`: when it is 0, set values directly.

---

## 8. Navigation

- **Bottom bar:** docked to the screen edge, `nav_bg`, 68dp tall above the system navigation area, top corners 24dp. Five items, left to right: **PC link** (where the app opens), **Transfer**, **Tools**, **Stats**, **Settings**. Each is an icon on a 56 × 32dp pill with a 12sp label under it (Medium when open), evenly spaced; the whole cell is the touch target. The open item's icon is `ink` on a white pill; the rest are white on the bar.
- **Tool screens** (Memory, Cleaner, Network, Battery, Battery saver, Freezer, Apps, Storage, F-Droid, Recover, Permissions, Hardware) open from tiles on Tools. While one is open, **Tools** is highlighted. The bottom bar stays visible.
- **Tapping the open item** scrolls its screen back to the top (`Screen.onReselect()` can do more, e.g. Settings returns to its list of pages).
- **Back:** `Screen.onBack()` first (to leave a sub-page). Then the screen this one was opened from: `MainActivity` remembers it when a tool screen opens (Stats › an app's details › Back returns to Stats). Then Tools for a tool screen, PC link for the other bar screens. From PC link, Back leaves the app. Screens opened from outside (a notification, a tile, automation) don't remember where the app was: Back goes to their parent.
- **Names:** each screen's `name` is its key for `host.show(…)` and the `screen` intent extra. Names from earlier versions keep working (`MainActivity.ALIASES`): `Home` → Tools, `Receive` / `Send` → Transfer on that side. Never rename a key without adding an alias.
- **Docked button:** a screen whose main button must always be reachable (Transfer › Send) passes it to `host.setDock(view)`; it sits on a strip of page colour just above the bar and the content gets extra bottom padding. The host clears it when another screen opens.
- **No back arrow in headers**, except inside Settings' pages.
- **Keyboard:** the bottom bar and any docked button hide while the keyboard is open.
- **System bars:** edge to edge, transparent. Status bar icons are dark in light theme. Navigation bar icons stay light because they sit on the dark bottom bar.
- **Adding a destination:** it belongs in the bottom bar only if people open it every session. Otherwise add a tile on Tools. Five is the maximum for the bar.

---

## 9. Components

All built by `Ui` (`host.ui`) unless noted. Use these before writing a new view.

### Structure

| Component | Call | Notes |
|---|---|---|
| Top bar | `ui.topBar(title, host::goHome, actionIcon, actionLabel) { … }` | 28sp Light title, one line. Optional single 42dp action button on the right; `actionLabel` is its spoken description. Returns the bar and the button. Every screen starts with it. |
| Subtitle | `ui.subtitle("…")` | 15sp line under the top bar on the bottom-bar screens: what the screen is for, or its state (Tools). 6dp below the top bar, 20dp above the next card. |
| Display heading | `ui.heading("Line one\n", "line two")` | 38sp. For intro screens only. |
| Section heading | `ui.section("Details") { /* trailing view */ }` | 22sp Light. May hold one trailing pill or link. |
| Hero card | `ui.heroCard(16) { … }` | The gradient. One per screen. |
| Card | `ui.glassCard(16) { … }` | The default container. Padding 6 when it holds `UiRows`. |
| Solid card | `ui.card(color(R.color.surface), 16) { … }` | When content behind must not show through. |
| Card header | `ui.cardHeader(icon, title)` | Icon chip, 15sp Medium title, 12sp subtitle. Returns the row and the subtitle view. |

### Actions

| Component | Call | Use for |
|---|---|---|
| Primary button | `ui.action("Clean now", R.drawable.ic_broom) { … }` | The one main action of a screen. 56dp, full width, ink fill. At most one visible at a time. |
| Pill button | `ui.pill("Stop", color(R.color.fab), color(R.color.on_fab)) { … }` | Secondary actions and per-row actions. 42dp. |
| Small pill | `ui.smallPill("Clear", filled = false, icon) { … }` | 36dp, 13sp. Actions in a card header or a section heading ("See all", "Refresh", "How"). Quiet (`glass_strong`) by default; `filled` for the one action that matters in that card. Also the Stats segment chips. |
| Quiet pill | `ui.pill("Details", color(R.color.glass_strong), color(R.color.ink)) { … }` | Lower-priority action next to a filled one. |
| Badge | `ui.pill("Estimate", color(R.color.chip), color(R.color.ink))` | No `onClick`: a non-interactive label. |
| Header button | `ui.circleButton(icon, description) { … }` | Icon-only action. The description is required. |
| Switch | `ui.toggle { on -> … }` | Ink track when on, `ink_faint` when off. |
| Checkbox | `ui.setCheck(imageView, checked)` | Round: filled check or empty ring. For multi-select lists. |
| Round chip | `ui.styleDayChip(textView, selected)` | Small one-of-several pickers. |
| Segmented switch | `segmented(ui, listOf("Today", "7 days")) { i -> … }` | Two or three equal choices that switch what the screen shows (Transfer's Receive \| Send, Stats' range). Returns the view and a setter. |

Changing a pill later: `ui.setLabel(tv, "Stopping…")` keeps its icon; `ui.stylePill(…)` restyles it.

Button hierarchy on one screen: one `action`, any number of pills, and destructive actions as a pill whose label says exactly what happens ("Delete 12 files"), followed by a confirmation dialog.

### Rows and lists

| Component | Call | Notes |
|---|---|---|
| Setting row with value | `UiRows.value(parent, icon, title, sub, onClick)` | Value on the right, chevron when tappable. |
| Setting row with switch | `UiRows.switch(parent, icon, title, sub) { wanted -> … }` | The whole row is the tap target. The handler decides whether the switch really moves. |
| Note in a card | `UiRows.note(parent, text)` | 13sp explanation. |
| Key and value | `ui.keyValue("Voltage")` | Key soft on the left, value Medium on the right, up to 3 lines. |
| List pill | `ui.listPill(icon, label) { … }` | Translucent pill row inside a hero or coloured card. |
| Note row | `ui.insight(icon, iconBg) { … }` | Icon, a sentence, arrow when tappable. For tips and findings. |
| Status label | `ui.dotLabel()` | 8dp dot plus text. Set both colour and words. On the hero, put it on a `glass` pill with `ink` text (PC link's Linked / Ready / Off). |
| Share row | `ui.shareRow(icon, fallbackIcon, title, figure, share, sub) { … }` | One app (or group) in a statistics list: icon, name, figure on the right, a 6dp share bar and a line under it ("23% · 14 opens"). The whole row is the target. |
| Empty card | `ui.emptyCard(icon, "Sentence.", "Action") { … }` | Empty, unavailable and needs-access states: an icon chip, one sentence, an optional filled small pill. |
| Personal data | `PrivacyBlur.always(view)` / `PrivacyBlur.ifFound(textView)` | Mark once, when building, every view that shows a name, number, address or message text, so Settings › Display › Blur personal data can blur it. `always` for a value that is personal whatever it says; `ifFound` for mixed text, blurred only while `Pii` finds personal data in it (`Pii.addKnown(name)` first for names). Never on a button: leave the name out of its label while the setting is on. |

### Dashboard

| Component | Call | Notes |
|---|---|---|
| Tile | `ui.tile(bg, title, icon) { … }` | Tools grid entry. Returns the box and a subtitle for a live value. |
| Action cell | (PcLinkScreen `actionCell`) | Quick action: 48dp rounded icon tile and a 13sp label under it, three across in a card. |
| Stat card | `ui.stat(bg, icon, caption)` | Caption and icon on top, 24sp value below. Two or three across. |
| Number row | `ui.numbers(listOf("Used", "Free", "Total"))` | Small labels over big numbers, equal widths. |

---

## 10. Data display

| View | Class | Use for | Colours |
|---|---|---|---|
| Ring gauge | `Ring` | One fraction of a whole (memory used, health score). A thick stroke, about a seventh of the diameter (16dp on a 120dp ring, 20dp from 140dp up), round ends. Text inside at 12sp or more, in `ink`. | `colors(track, start, end)`: a lime gradient on a `glass_strong` or `chip` track |
| Donut | `Ring` with `segments` | A whole split into parts (storage). 2.5° gaps. | Category colours, with a legend |
| Progress bar | `Meter` | Linear progress or a share of a total inside a row. Height 6 to 10dp. | `colors(track, fill)` |
| Bar chart | `BarChart` | Values per slot (a day, an app). Bars can stack several values (`colors(stack, …)`, bottom first); 12sp labels under the bars, the `highlight` one (today) in `ink`, the others `ink_soft`. An empty slot shows a short stub. 150dp tall in a card with a header saying the range ("Last 7 days"). | `green_dot` for one series; `cat_blue`, `green_dot`, `cat_yellow` for stacks, with a `dotLabel` legend |
| Line chart | `LineChart` | A value over time. Smooth line, 2.5dp, dashed guides, a dot on the latest value. Optional soft fill and marker. | `colors(line, guide, dotRing)`: `chart`, `line`, card colour |
| Battery | `BatteryView` | Battery level only | See `BatteryScreen` |
| Wave | `Wave` | Decoration along the bottom of a card. Carries no data. | One tint |

Rules:

- A gauge always has its figure as text beside or inside it. The picture is never the only way to read the value.
- Before the first reading, values show an en dash (`–`), never `0`.
- Sizes use `fmtSize()` / `sizeParts()`: 1000-based units to match Android's storage screens.
- Charts state their range in words ("Last 24 hours", "Last 7 days") and mark gaps honestly (dashed stretches where there are no samples).
- A chart is never the only way to read its figures: set a `contentDescription` with the values in words, and list them below.
- An estimate is labelled as an estimate next to the figure.
- Set `floorZero = true` when the quantity is an amount, so small changes aren't exaggerated.

---

## 11. Screen patterns

### Anatomy of a tool screen

```
┌──────────────────────────────┐
│ Battery                  (⚡) │  top bar: title + one action
│                              │  24
│ ╭──────────────────────────╮ │
│ │  [gauge]   86%           │ │  hero card: the answer
│ │            Charging      │ │
│ ╰──────────────────────────╯ │  12
│ ╭──────────────────────────╮ │
│ │ (✿) Good                 │ │  status or note row
│ ╰──────────────────────────╯ │  26
│ Details                      │  section heading
│ ╭──────────────────────────╮ │  12
│ │ Temperature        31 °C │ │  card of key/value rows
│ │ Voltage            4.2 V │ │
│ ╰──────────────────────────╯ │  26
│ Power saving                 │
│ ╭──────────────────────────╮ │
│ │ (☘) Battery Saver   [▬●] │ │  card of setting rows
│ │ (❄) Freezer            › │ │
│ ╰──────────────────────────╯ │
├──────────────────────────────┤
│  ▭     ⇄     ▦     ▮     ⚙   │  bottom bar: PC link, Transfer, Tools, Stats, Settings
└──────────────────────────────┘
```

Order of content: the answer, then what it means, then the details, then things to do about it, then links to related screens.

### PC link (where the app opens)

Top bar and subtitle; a hero card with the link's state (status pill, the computer's name or "Ready", how and how long, one primary Start/Stop button); a card of quick actions; then sections: Today (number row and a link to Stats), Recent activity (rows with a time), What the computer can read, Connect (USB and Wi‑Fi cards with numbered steps).

### Tools

Top bar and a subtitle with the health verdict, a hero card that rotates through readings, the storage card, then "All tools": a two-column grid of tiles, each showing a live value in its subtitle.

### Stats

Top bar and subtitle, a scrolling row of segment chips, the range switch where it applies, then per segment: a hero with the total, a chart card, a section heading with the count, and a card of share rows (15 shown, "Show all N"). A note at the end of the list says where the figures come from. Missing access replaces all of it with an empty card and the button to grant it.

### Scan, review, act (Cleaner, Freezer, Apps)

1. **Before:** hero with the last known figure and a primary button ("Scan").
2. **Working:** the ring spins, the button is disabled and relabelled ("Scanning…"), results appear as they are found.
3. **Review:** groups as cards with a count and size each; items with round checkboxes; safe items preselected, risky ones not.
4. **Act:** the primary button states the action and amount ("Delete 1.2 GB"). A confirmation dialog follows.
5. **After:** the hero shows what was done ("1.2 GB freed") and the lists refresh.

### Guided steps (PC link, Hardware test)

Numbered steps in a card, one instruction per step in plain words, the current step emphasised. Where the phone can tell the result itself, show it; otherwise offer "Passed" and "Failed" pills.

### Permission request

Explain first, in the screen: what the access is for, in one sentence, with a pill button that opens the system dialog or the right Settings page. Never show a system permission dialog on screen open. If the person declines, the feature's row stays, greyed, with the reason and a way to grant it later.

`Perms.kt` holds each access the app uses (what it is for, its state, and how to ask: Android's dialog, or the right Settings page for special access and for permissions refused for good). At the point of use, `Perms.ensure(activity, access, why)` returns true when it is allowed, or shows the one-sentence explanation with **Allow** / **Not now**. The welcome tour's **Allow what you'll use** page lists `Perms.TOUR` with each state and an **Allow** pill, re-read in `onResume`; nothing on it is required.

---

## 12. States: loading, empty, error, unavailable

Every screen and every card that loads data handles all of these.

| State | What to show |
|---|---|
| **Not yet loaded** | `–` in value fields. No skeletons, no layout shift when values arrive. |
| **Working** | `Ring.busy = true` for a headline measure; otherwise change the button label ("Checking…") and disable it. Work runs through `Screen.background { … }`, never on the main thread. |
| **Empty** | One sentence in a card saying what is true and, if useful, what to do: "No large files found." Not a blank card and not an illustration. `ui.emptyCard()` or `UiRows.note()` inside the list's card. |
| **Not enough data** | "Not enough data yet", with when to expect it if known. |
| **Failed** | What happened and what to try, in the place where the result would have been. `danger` for the icon or title only. Offer a retry pill when retrying can help. |
| **Unavailable on this phone** | Keep the row, grey it with `Row.available(false, ui, why = "Needs Android 14 or newer")`. |
| **Needs access** | Keep the row or card, state what is missing, add a pill to grant it. |
| **Offline or not connected** | A `dotLabel` with a grey dot and the words; the action that needs the connection is disabled with the reason below it. |

---

## 13. Dialogs, toasts and confirmation

- **Dialogs** are the system `AlertDialog` (they pick up Poppins and the accent from the theme). Use `UiRows.pick()` for one-of-many and `UiRows.message()` for a notice.
- **Confirm before** deleting, uninstalling, force-stopping a system or on-screen app, and anything that can't be undone.
  - Title: the action as a question or statement ("Delete 12 files?").
  - Body: the count and the consequence ("12 files and folders will be deleted. They can't be recovered.").
  - Buttons: the verb on the positive button ("Delete"), "Cancel" on the negative. Never "Yes"/"No"/"OK" for a destructive choice.
- **Don't confirm** reversible actions such as switches, filters or starting a scan.
- **Toasts** only for a brief result of something the person just did, when the screen doesn't already show it ("Copied"). Never for errors that need action, and never as the only record of a failure.
- **Long work** that continues after leaving the app uses a foreground service with a notification that says what is happening and offers a way to stop it.

---

## 14. Writing

**Voice:** plain, direct, calm. Written for someone who doesn't know what ADB is.

| Do | Don't |
|---|---|
| "Free memory" | "Kill background processes" |
| "Needs Android 14 or newer" | "Unsupported API level" |
| "They can't be recovered." | "This action is irreversible!" |
| "Not enough data yet" | "N/A" |
| "Couldn't reach the computer. Check that both are on the same Wi‑Fi." | "Error: connection failed (ECONNREFUSED)" |

Rules:

- **Sentence case** everywhere: titles, buttons, rows. Only proper names are capitalised (Shizuku, Wi‑Fi, Battery Saver as Android's own setting name).
- **Buttons are verbs**, two words at most where possible: "Scan", "Clean now", "Stop", "Open settings".
- **Row titles are nouns**, row explanations say what the thing does in one line.
- **No exclamation marks**, no "please", no "oops", no "successfully".
- **Numbers:** a space between number and unit ("1.2 GB", "31 °C"), no space before `%`. Counts get their noun and correct plural ("1 item", "12 files").
- **Time:** always through `ago(time)`: "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago", then the date. Never "0 minutes ago". Lengths of time through `duration(ms)`: "45 s", "12 min", "3 h 5 min" (number and unit never split across lines).
- **Say why** when something is limited: name the cause (Android version, missing access) and the way forward.
- **Names of things:** the Wi‑Fi file screen is "Transfer", with "Receive" and "Send" sides; the feature is "Wi‑Fi receive" / "Wi‑Fi send". The desktop app is "Andromon"; before it says its name, the computer is "your computer". The link to it is "PC link". The junk tool is "Cleaner". Use the same word for the same thing on every screen.
- **Wi‑Fi** is written with a non-breaking hyphen (`‑`, U+2011) so it never splits across lines.
- User-visible strings are currently English and mostly inline in Kotlin. Keep each one a complete sentence or label (no concatenating fragments) so they can move to `strings.xml` later.

---

## 15. Accessibility

- **Touch targets:** 48 × 48dp minimum. The 42dp pill and header button fall short by themselves: give them at least 6dp of clear space around, or make the whole row the target (as `UiRows.switch` does).
- **Spoken labels:** every icon-only control has a `contentDescription` that names the action ("Refresh"), not the picture ("Arrow icon"). `circleButton` and `tile` require one. Decorative icons next to a text label get none.
- **Custom views** (`Ring`, `Meter`, `LineChart`, `BatteryView`) draw pixels only. Set a `contentDescription` on them with the value in words ("Memory, 62% used") and update it when the value changes, or mark them not important for accessibility when the same figure is in text right beside them.
- **Grouping:** a row is one focus stop. Set the tap listener on the row, not on the parts inside it.
- **Contrast:** follow the table in [Colour](#2-colour). Small text is `ink`, or `ink_soft` on a card.
- **Text scaling:** sp everywhere. Test at the largest system font size: titles ellipsise, explanations wrap, figures stay whole.
- **Colour independence:** every status has words; every chart segment has a legend row.
- **Motion:** respect the system animation scale (see [Motion](#7-motion)).
- **Right to left:** start/end margins and paddings only. Arrows and chevrons should mirror.

---

## 16. Dark theme

The app follows the phone by default; Settings offers Same as phone, Light and Dark. Night values come from `values-night/`.

What changes besides the obvious inversion:

- **The primary button turns lime.** `fab` is ink in light and `#E3F786` in dark, with dark text. That's why code uses `fab`/`on_fab` and never `ink`/white for buttons.
- **The hero gradient goes deep olive** (`#55691F` → `#334414`) with light text, instead of bright lime with dark text.
- **Glass gets much fainter** (7% and 13% white), so layering is subtle. Don't stack more than three layers.
- **Shadows vanish.** Another reason not to rely on elevation.
- **`good` flips** from dark green to bright lime, and `danger` to a lighter red.

Rules:

- Never write a hex colour or `Color.WHITE`/`Color.BLACK` in a screen, except for marks drawn on a fixed-colour fill (a white glyph on a `good` badge).
- Colours are read when a view is built. Changing the theme in Settings recreates the activity, so every screen is rebuilt; a custom view that caches colours must take them through a `colors(…)` call, not read them once at class load.
- Check every new screen in both themes before it ships.

---

## 17. Building a screen, end to end

1. **Decide the headline.** One sentence: what does the hero card say? If you can't say it, the screen isn't ready to design.
2. **Sketch the order:** answer, meaning, details, actions, related links.
3. **Pick components** from [section 9](#9-components). Write a new one only if none fits, and add it to `Ui.kt` so the next screen can use it.
4. **Write the strings** following [section 14](#14-writing), including every state in [section 12](#12-states-loading-empty-error-unavailable).
5. **Build it:**

```kotlin
internal class ExampleScreen(host: MainActivity) : Screen(host) {
    override val name = "Example"
    override val navIcon = R.drawable.ic_info
    override val navColor = R.color.bg

    private lateinit var figure: TextView
    private lateinit var ring: Ring
    private lateinit var scan: TextView

    override fun build(): View = ui.column {
        addView(ui.topBar("Example", host::goHome, R.drawable.ic_refresh, "Refresh") { load() }.first)

        addView(ui.heroCard(18) {
            addView(ui.row {
                ring = Ring(context).apply {
                    colors(ui.color(R.color.glass_strong), ui.color(R.color.green_dot), ui.color(R.color.good))
                }
                addView(ring, ui.lp(w = ui.dp(120), h = ui.dp(120)))
                figure = ui.text(48f, ui.color(R.color.ink)) { text = "–"; letterSpacing = -0.03f }
                addView(figure, ui.lp(w = 0, weight = 1f, start = 16))
            })
        }, ui.lp(top = 24))

        addView(ui.section("Details"), ui.lp(top = 26))
        addView(ui.glassCard(6) {
            val rows = UiRows(host, ui)
            rows.value(this, R.drawable.ic_storage, "Storage", "What is using space") { host.show("Storage") }
        }, ui.lp(top = 12))

        scan = ui.action("Scan", R.drawable.ic_search) { load() }
        addView(scan, ui.lp(top = 20))
    }

    override fun onShow() = load()

    private fun load() {
        ring.busy = true
        ui.setEnabled(scan, false); ui.setLabel(scan, "Scanning…")
        background({ measure() }) { result ->
            ring.busy = false
            ui.setEnabled(scan, true); ui.setLabel(scan, "Scan")
            result.onSuccess { ring.fraction = it; figure.text = ui.percent((it * 100).toInt()) }
                .onFailure { figure.text = "–" /* and say what went wrong in a note */ }
        }
    }

    private fun measure(): Float = 0.62f
}
```

6. **Register it** in `MainActivity.screens`; add its name to `navOrder` only if it belongs in the bottom bar, otherwise add a tile on Tools.
7. **Stop work in `onHide()`**: receivers, timers, sensors, animations.
8. **Check it** against the list below on a small phone and a large one, in both themes.
9. **Ship it:** bump `versionCode` in `app/build.gradle.kts` and `COMPANION_VERSION` in `pc/src/app.js` together, and describe the screen in `README.md`.

---

## 18. Review checklist

**Look**
- [ ] Exactly one hero card; no other gradient
- [ ] No hex colours, no hard-coded white or black in the screen
- [ ] Only text sizes from the type scale; nothing under 12sp
- [ ] Gaps from the rhythm table (24 / 12 / 26 / 12)
- [ ] Every tappable surface ripples; nothing else does
- [ ] Right in light and dark

**Behaviour**
- [ ] Values show `–` before data arrives; no layout jump when it does
- [ ] Working, empty, failed, unavailable and needs-access states all exist
- [ ] Nothing slow on the main thread
- [ ] Back does the expected thing; the bottom bar highlights the right item
- [ ] Destructive actions confirm with count and consequence
- [ ] Listeners and timers stop in `onHide()`

**Words**
- [ ] Sentence case; buttons are verbs
- [ ] Limits explained with cause and next step
- [ ] Estimates labelled as estimates
- [ ] Same names as the rest of the app ("Transfer", "PC link", "Andromon")
- [ ] Times through `ago()` and `duration()`

**Access**
- [ ] Icon-only controls have a spoken label
- [ ] Targets at least 48dp, or the row is the target
- [ ] Works at the largest font size and at 320dp width
- [ ] Status readable without colour
- [ ] Names, numbers, addresses and message text are marked for Blur personal data (`PrivacyBlur`)
- [ ] Small secondary text sits on a card, not on the page or the hero

---

## 19. Known gaps

Where the app doesn't yet meet this guide. Fix when touching the area.

- **`ink_soft` on the page background** is 4.1:1 in light theme, just under the 4.5:1 needed for small text. `dotLabel` and some captions sit directly on the page.
- **`good` and `danger` as small text** on light cards measure 3.6:1 and 4.0:1.
- **42dp pills and header buttons** are under the 48dp target size.
- **Custom views have no spoken description** by default.
- **Animation scale is not checked** by `Ring`.
- **Strings are inline** in Kotlin, English only.
- **Legacy colour names** (`salmon`, `purple`, `blue_tile`, `lime` meaning ink in light theme) are misleading. `R.color.lime` in particular is `#292A2C` in light theme; use `fab`/`on_fab` for buttons and `good_bg` for the lime tint.
- **Poppins is subset to Latin.** Text in other scripts falls back to the system font.
- **Older tool screens** (Cleaner, Freezer, Battery saver, Hardware…) still use some 34dp pills and the legacy pastel names for card tints. PC link, Transfer, Tools, Stats and Settings follow this guide; bring the others over when touching them (`ui.smallPill`, `glassCard`, `cardHeader`).
