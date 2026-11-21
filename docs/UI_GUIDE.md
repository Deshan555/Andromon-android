# Andromon Companion: UI and theming guide

How the phone app's screens are built and themed, for anyone changing or adding one. Everything here is
taken from the code. When the code and this guide disagree, the code wins; fix the guide.

- [The approach](#the-approach)
- [Add a screen](#add-a-screen)
- [Layout](#layout)
- [Type](#type)
- [Components](#components)
- [Theming](#theming)
- [Accessibility](#accessibility)
- [Personal data and the blur](#personal-data-and-the-blur)
- [Icons](#icons)
- [Loading, empty and error states](#loading-empty-and-error-states)
- [Words on screen](#words-on-screen)
- [Checklist](#checklist)

## The approach

- **Plain framework views, built in Kotlin.** There's no AndroidX, Material, Compose or layout XML. The APK
  stays around 570 KB, and the app runs the same on every Android version it supports.
- **One small toolkit: `Ui`** (`Ui.kt`). It makes text, rows, cards, pills, switches, loading placeholders
  and charts in the brand look. Screens use `ui.*` and never style a view by hand when a helper exists.
  `UiRows` (`UiRows.kt`) adds the setting rows used by Settings, the Freezer and the Battery saver.
- **Colours come from resources**, always through `ui.color(R.color.…)`. That way light and dark mode, and
  high contrast, work without any code in the screen.
- **The look:** a soft grey page, off-white rounded cards, one lime-to-green **hero card** per screen for
  its headline figure, big light headings, dark pill buttons, and a dark bottom bar. No shadows, no
  italics.

| File | What it holds |
|---|---|
| `Ui.kt` | The toolkit: views, cards, pills, switches, loading states, `Ring`, `Meter`, `LineChart`, `BarChart`, `segmented()`, `ago()`, `duration()`, `fmtSize()` |
| `UiRows.kt` | Setting rows (value, switch), notes, pick-one and message dialogs |
| `Screen.kt` | The base class every page extends |
| `MainActivity.kt` | Hosts the screens: scrolling page, bottom bar, insets, Back, theme and text size |
| `Prefs.kt` | Theme and accessibility settings (`theme`, `textSize`, `highContrast`, `boldText`, `reduceMotion`) |
| `PrivacyBlur.kt` | Blurs personal data on screen |
| `res/values/colors.xml`, `res/values-night/colors.xml` | Every colour, light and dark |
| `res/values*/themes.xml` | `AppTheme` and the start-up `LaunchTheme` |
| `res/font/` | Poppins: light, regular, medium, semibold |
| `res/drawable/ic_*.xml` | Line icons (Lucide) |

## Add a screen

1. **Write the class.** Extend `Screen`, give it a name, a bottom-bar icon and its window colour, and build
   its view once in `build()`:

   ```kotlin
   internal class ExampleScreen(host: MainActivity) : Screen(host) {
       override val name = "Example"
       override val navIcon = R.drawable.ic_info
       override val navColor = R.color.bg          // every screen uses bg

       private lateinit var figure: TextView

       override fun build(): View = ui.column {
           addView(ui.topBar("Example", host::goHome).first)

           addView(ui.heroCard(18) {                  // one per screen: the headline figure
               addView(ui.cardHeader(R.drawable.ic_info, "Something").first)
               figure = ui.text(50f, color(R.color.ink), ui.regular) { text = "–"; letterSpacing = -0.03f }
               addView(figure, ui.lp(top = 14))
           }, ui.lp(top = 24))

           addView(ui.section("Details"), ui.lp(top = 26))
           addView(ui.glassCard(16) {
               addView(ui.keyValue("Status").first)
           }, ui.lp(top = 12))
       }

       override fun onShow() {
           ui.loading(figure, true)
           background({ work() }) { r ->
               ui.loading(figure, false)
               figure.text = r.getOrNull()?.toString() ?: "–"
           }
       }
   }
   ```

2. **Register it** in the `screens` list in `MainActivity`. The bottom bar shows only the screens named in
   `navOrder` (PC link, Transfer, Tools, Stats, Settings); other screens are opened with
   `host.show("Example")`.
3. **Link to it**, usually with a tile in `ToolsScreen`:
   `ui.tile(color(R.color.surface), "Example", R.drawable.ic_info) { host.show("Example") }`.
4. **Use the lifecycle:** `onShow()` / `onHide()` start and stop timers and listeners, `onBack()` returns
   `true` when it closed a sub-page, and `background(work, done)` runs work off the main thread and calls
   `done` only if the activity still exists.

## Layout

Everything is measured in dp through `ui.dp()` and `ui.lp(...)`. `ui.lp(top = 12, start = 8)` takes dp.

**The page**
- The page scrolls (`ScrollView`) and is drawn behind the system bars. `MainActivity.applyInsets()` adds
  their size as padding, so screens never handle insets themselves.
- Page padding: **20 dp** left and right, **16 dp** at the top, plus the system bars. The bottom leaves room
  for the bar.
- The bottom bar is **68 dp**, docked to the bottom edge with 24 dp rounded top corners. It hides while the
  keyboard is up.

**Spacing**

| Between | dp |
|---|---|
| The title bar and the first card | 24 |
| A section heading and the card above it | 26 |
| A section heading and its card | 12 |
| Cards in a row or a column | 12 |
| Parts inside a card | 6–14 |
| Inner card padding | 16 (`glassCard`), 18 (hero) |

**Corners**

| Shape | Radius (dp) |
|---|---|
| Cards (`card`, `glassCard`, `heroCard`) | 26 |
| Dashboard tiles (`tile`) | 24 |
| Stat cards (`stat`), notes (`insight`) | 22, 24 |
| Rows that light up when pressed | 20 |
| Pills, segmented control, list pills | 50 (fully round) |
| Icon tiles | 0.32 × the tile's size |
| Bottom bar's open item | 16 |

**Touch targets:** at least **42 dp** high (`pill`, `circleButton`). Main actions are 56 dp (`action`),
small pills in rows are 36 dp. Rows are tapped as a whole: a switch row's switch isn't a separate target.

## Type

Poppins everywhere (`res/font`, subset to Latin). `AppTheme` sets it for system widgets and dialogs too,
and `ui.text()` picks the weight. Turn off font padding (`ui.text` does) so text lines up with icons.

| Weight | `Ui` field | Used for |
|---|---|---|
| Light | `ui.light` | Page titles, headings, section headings |
| Regular | `ui.regular` | Body text and big figures |
| Medium | `ui.medium` | Card titles, values, buttons, the open bottom-bar label |
| SemiBold | `ui.bold` | Rare emphasis; the heaviest weight in the design |

There are no italics: `ui.lightItalic` is the light face, upright.

| Role | Size (sp) | Weight | Helper |
|---|---|---|---|
| Expressive heading | 38 | Light, −0.03 tracking | `ui.heading()` |
| Page title | 30 | Light, −0.02 | `ui.pageTitle()` |
| Screen title bar | 28 | Light, −0.02 | `ui.topBar()` |
| Section heading | 22 | Light, −0.02 | `ui.section()` |
| Hero figure | 50 | Regular, −0.03 | `ui.text(50f, …)` |
| Stat figure | 24 | Regular | `ui.stat()`, `ui.numbers()` |
| Tile title | 17 | Regular | `ui.tile()` |
| Main button | 16 | Medium | `ui.action()` |
| Subtitle under a title | 15 | Regular, soft ink | `ui.subtitle()` |
| Card and row title | 15 | Medium | `ui.cardHeader()`, `UiRows` |
| Body, values, pills | 14 | Regular / Medium | `ui.text(14f, …)`, `ui.pill()` |
| Small pill | 13 | Medium | `ui.smallPill()` |
| Second line, captions | 12–13 | Regular, soft ink | `UiRows`, `ui.cardHeader()` |
| Bottom-bar label | 12, up to 14 | Regular | `MainActivity.buildNav()` |

Units read as a smaller, softer part of a figure: `ui.percent(92)` → "92%", `ui.withUnit("1.2", "GB")`.
Sizes go through `fmtSize()` / `sizeParts()`, times through `ago()` and `duration()`, so every screen says
them the same way.

## Components

Reach for these before building anything by hand.

**Structure**

| Helper | Use it for |
|---|---|
| `row { }`, `column { }`, `spacer()`, `lp(...)` | Layout. `row` centres vertically and turns off baseline alignment, which used to clip wrapped text |
| `topBar(title, onBack, actionIcon?, actionLabel?, onAction?)` | The one-line title at the top of a screen, with an optional round action. There's no back button: Back and the bottom bar leave a screen |
| `section(title) { }` | A heading between groups, with room on the right for a link ("Filter ›") |
| `subtitle(text)` | One line under a title saying what the screen is about or its state |

**Cards**

| Helper | Use it for |
|---|---|
| `heroCard(pad) { }` | The screen's headline, on the lime-to-green gradient. **One per screen** |
| `glassCard(pad) { }` | The normal card on the grey page |
| `card(bg, pad) { }` | A card in another colour (`surface`, a pastel). Passing `color(R.color.hero)` gives the gradient |
| `cardHeader(icon, title)` | Icon chip, title and a subtitle you fill in later |
| `tile(bg, title, icon, onClick)` | Dashboard tile with an arrow; returns its subtitle for a live value |
| `stat(bg, icon, caption)` | Small card with a caption, icon and a figure |
| `emptyCard(icon, message, action?)` | Nothing to show, or not available, with an optional fix |

**Rows and values**

| Helper | Use it for |
|---|---|
| `keyValue(key)` | Label on the left, value on the right |
| `listPill(icon, label, onClick?)` | Round, translucent row inside a coloured card |
| `insight(icon, iconBg, onClick?)` | A note or tip with an icon, and an arrow when tappable |
| `dotLabel()` | "● Current" status line |
| `shareRow(...)` | An app or group in a stats list, with a bar for its share |
| `numbers(labels)` | A row of small labels over big figures |
| `UiRows.value(...)`, `UiRows.switch(...)`, `UiRows.note(...)` | Setting rows: icon, title, explanation, and a value, switch or chevron |

**Actions and controls**

| Helper | Use it for |
|---|---|
| `action(label, icon?, onClick)` | The screen's main button, 56 dp, dark (lime in dark mode) |
| `pill(label, bg, fg, icon?, onClick?)` | Other buttons; with no `onClick` it's a badge |
| `smallPill(label, filled, icon?, onClick?)` | Buttons inside cards and rows. `filled = true` for the primary one |
| `circleButton(icon, description, onClick)` | Round icon button, as in the title bar. Always give it a description |
| `toggle()` | The switch: dark track when on, grey when off |
| `segmented(ui, labels, onPick)` | Two to four options in a pill track |
| `styleDayChip(tv, selected)` | Round day chips (Mo Tu We) |
| `setCheck(iv, checked)` | Round checkbox |
| `setEnabled(v, enabled)` | Disable a control; it fades to 40% |
| `UiRows.pick(...)`, `UiRows.message(...)` | Dialogs (system `AlertDialog`, themed by `AppTheme`) |

**Pictures of data**

| View | Use it for | Colours |
|---|---|---|
| `Ring` | A percentage around an icon or figure; `busy = true` spins | track `glass_strong` or `chip`, fill `ink` |
| `Meter` | A thin bar for a share | track `chip`, fill `green_dot` |
| `LineChart` | A value over time; `marker` highlights the latest point | line `chart`, guides `glass_strong`, marker `marker` |
| `BarChart` | Values per day or per hour | |
| `BatteryView`, `Wave` | The battery screens | |

Charts show one measure in the brand green. Don't give series their own colours; put two measures in two
charts.

## Theming

### How it works

- `AppTheme` (`res/values/themes.xml`) is `Theme.DeviceDefault.Light.NoActionBar` in light mode and
  `Theme.DeviceDefault.NoActionBar` in dark (`values-night`). It sets the window background (`bg`), the
  accent for system widgets (`ink`) and Poppins.
- **Dark mode is a second set of colours with the same names** in `res/values-night/colors.xml`. Android
  picks the right file, so screens never check for dark mode.
- **Settings › Display › Theme** (Same as phone, Light, Dark) is applied in
  `MainActivity.attachBaseContext()`: it overrides the night bit before any resource is read, and changing
  it calls `recreate()`. `OnboardingActivity` and `UnlockActivity` do the same. A new activity must too.
- The window draws edge to edge with transparent system bars. Status-bar icons are dark in light mode and
  light in dark mode. Navigation-bar icons always stay light, because they sit on the dark bottom bar.
- **Start-up:** `LaunchTheme` shows `splash_bg` (the logo's dark backdrop, the same in both modes). Android
  12 and newer use their own splash with an empty icon (`values-v31`, and `values-night-v31` because night
  outranks v31). Then `Splash.kt` draws the logo and progress bar, and `onCreate` switches to `AppTheme`.

### The colours

The ones to use in new code:

| Token | Light | Dark | Role |
|---|---|---|---|
| `bg` | `#E6E6E4` | `#151617` | The page |
| `surface` | `#F5F5F3` | `#212224` | Tiles, raised parts |
| `glass` | `#A6FFFFFF` | `#12FFFFFF` | Normal cards (`glassCard`), segmented track |
| `glass_strong` | `#E6FFFFFF` | `#22FFFFFF` | Icon chips, round buttons, notes, chart guides |
| `chip` | `#0F000000` | `#1AFFFFFF` | Small marks on cards, skeleton blocks, meter tracks |
| `ink` | `#292A2C` | `#F2F2F0` | Text and icons |
| `ink_soft` | `#6C6D70` | `#A4A5A7` | Second lines, captions, keys |
| `ink_faint` | `#A4A5A7` | `#66676A` | Chevrons, empty checkboxes, switch off |
| `line` | `#DCDCD9` | `#2E2F32` | Hairlines |
| `fab` / `on_fab` | `#292A2C` / white | `#E3F786` / `#1B1C1D` | **Primary action** (main buttons, filled pills, selected chips) and its text |
| `ripple` | 8% black | 16% white | Touch feedback (`pressable`) |
| `hero_start` → `hero` → `hero_end` | `#EAF9A8` → `#C6EC50` → `#A3D63B` | `#55691F` → `#43561A` → `#334414` | The hero card's gradient, top left to bottom right |
| `good` / `good_bg` | `#5F8F12` / `#E3F786` | `#B7E356` / `#3A4A18` | Healthy, done |
| `danger` | `#D9443F` | `#F08A86` | Errors, delete |
| `yellow_line` | `#D9A514` | `#F2CB5C` | Warning |
| `chart` | `#9CD237` | `#9CD237` | Chart lines |
| `marker` | `#E2504C` | `#F07A76` | The highlighted point on a chart |
| `nav_bg`, `nav_icon`, `nav_active`, `centre_bg`, `centre_icon` | | | Bottom bar only |
| `cat_*` | | | Parts of a whole (storage breakdown, junk groups), in a fixed order |
| `splash_bg` | `#111317` | `#111317` | Start-up only |

The brand colours behind these are **lime `#E3F786`**, **green `#9CD237`** and **ink `#292A2C`**, the same
as on the desktop app and the website.

**Older names.** `salmon`, `peach`, `lavender`, `mint`, `coral`, `yellow`, `pink`, `olive`, `sky`, `cream`,
`green`, `green_tile`, `purple`, `blue`, `blue_tile` and others come from an earlier pastel design. They're
now mapped onto two lime tints, and some names no longer match their colour. Two catch people out:
**`lime` and `accent` are ink in light mode** (lime only in dark), so use `fab` for a primary action.
Don't use the older names in new code; pick from the table above.

### Rules

- **Always `ui.color(R.color.x)`**, never `ctx.getColor()` or a hex value in Kotlin. `ui.color()` applies
  high contrast; the others don't.
- **A new colour goes in both** `values/colors.xml` and `values-night/colors.xml`, with the same name.
  Choose the dark value for the dark page; don't invert the light one. Check that text on it is readable in
  both (4.5:1 for body text).
- **Text uses ink tokens** (`ink`, `ink_soft`), not a status or chart colour. A coloured icon or dot next to
  the text carries the meaning.
- **One hero card per screen.** If everything is highlighted, nothing is.
- **Status colours (`good`, `danger`, `yellow_line`) always come with words or an icon**, never colour
  alone.
- **No shadows.** `ui.elevate()` is still there, but the dark-mode `shadow` is transparent and cards are
  flat in both modes.

### Changing the brand

1. Change the values in both `colors.xml` files. The brand mostly lives in `fab`, `hero_*`, `good*`,
   `chart`, `green_dot`, the pastel tints and `nav_*`.
2. For another typeface, replace the `.ttf` files in `res/font` (keep the names, or update `Ui.font()` and
   `res/font/poppins.xml`) and its licence in `res/raw`.
3. Replace the logo (`splash_window`, the launcher icon in `mipmap-anydpi-v26`) and `splash_bg`.
4. Open every main screen in light and dark, with high contrast on, and take new screenshots for
   `docs/screenshots`.

## Accessibility

The settings live in **Settings › Display** (`Prefs.kt`), and each one recreates the activity, so `Ui`
reads them once:

| Setting | What the code does |
|---|---|
| Text size: Same as phone, Large (×1.15), Larger (×1.3), Largest (×1.5) | Multiplies the phone's font scale in `attachBaseContext` |
| High contrast | `ui.color()` turns `ink_soft` into `ink`, and `ink_faint` and `line` into `ink_soft` |
| Bold text | Every weight moves one step heavier (light → regular → medium → semibold) |
| Reduce motion | `Prefs.reduceMotion()` is true when this is on **or** the phone's animations are off. Check it before animating or smooth-scrolling |

What every screen does:
- **Text sizes in sp, layouts that grow.** Don't fix a text view's height. Allow two lines where a title
  can wrap, and end with `…` (`TruncateAt.END`) where it can't. Rows in `UiRows` allow four lines of
  explanation.
- **Headings are marked as headings.** `pageTitle`, `heading`, `topBar` and `section` do it, so screen
  readers can jump between them.
- **Every control has a name.** Icon-only buttons get `contentDescription`. Tappable rows and tiles read
  out their title (and values: `shareRow` reads "Chrome, 2 h, 23% · 14 opens").
- **Decoration is hidden** from screen readers (`IMPORTANT_FOR_ACCESSIBILITY_NO`): chart bars, the bottom
  bar's icon and label (its cell carries the name), skeletons (a list reads "Loading" once).
- **Touch targets at least 42 dp**, ideally 48.
- **Never colour alone** for a state: add a word or an icon.

## Personal data and the blur

**Settings › Display › Blur personal data** blurs names, numbers, addresses and messages, so screenshots
are safe to share. A tap shows a value for 5 seconds. Android 12 and newer blur the view; older versions
cover each line of text with a bar. Screen readers still read the value.

When you build a view that can show personal data, mark it once:

```kotlin
PrivacyBlur.always(computerName)   // always personal: a name, a number, an IP address
PrivacyBlur.ifFound(messageText)   // mixed text: Pii decides what to blur
```

The mark follows the view's text and the setting by itself, so the code that sets the text stays the same.

## Icons

- **Lucide** line icons (ISC licence, `res/raw/lucide_isc.txt`), as vector drawables in `res/drawable`
  named `ic_<name>.xml`. Start each file with a comment naming the Lucide icon.
- 24 × 24 viewport, **2-unit stroke, round caps and joins**, no fill, drawn in black. Colour is applied
  when the icon is used: `ui.icon(R.drawable.ic_x, color(R.color.ink))` or `ui.iconView(...)`. Don't put
  colours in the XML.
- Sizes: 15–20 dp inside chips and tiles, 20 dp for row chevrons and arrows, 26 dp on dashboard tiles.
- Icon holders: `chipIcon` (white circle on cards), `roundIcon` (circle in any colour), `iconTile`
  (rounded square), `badge` (scalloped circle).

## Loading, empty and error states

- **Show the layout straight away** and fill in values when they arrive; figures start as "–".
- **Lists that are loading:** `ui.skeletonList(count, ...)`, shaped like the real rows.
- **A single figure:** `ui.loading(value, true)` swaps it for a placeholder of the same height (a spinning
  ring for big figures, a shimmering bar for small ones). Call it with `false` when the value is ready.
- **Work in progress:** `ui.spinner()`, or `Ring` with `busy = true`.
- **Nothing to show, or not possible on this phone:** `ui.emptyCard(icon, "One sentence saying why", "Fix",
  ::fix)`. For a setting that doesn't apply, `Row.available(false, ui, "Why")` greys out the row and
  explains it.
- **Errors** are a sentence in the card where the thing would be, with a way to try again. Keep toasts for
  short confirmations ("Copied"); anything the person needs to read or act on goes on the screen.

## Words on screen

- **Plain words, sentence case:** "Battery saver", not "Battery Saver" or "BATTERY SAVER". Section headings
  are a word or two.
- **Say what happened and what to do:** "No apps use the camera" or "Turn on Usage access to see this",
  not "Error 3" or "No data".
- **Status as words**, with a `·` between parts: "Normal · plenty of free memory".
- **Buttons are verbs:** "Boost", "Clean", "Send".
- **Same words as the desktop app** for the same thing: PC link, Transfer, Freezer, Battery saver.

## Checklist

Before committing a UI change:

- [ ] Built with `ui.*` and `UiRows`; no hex colours or `getColor()` in Kotlin
- [ ] Any new colour is in both `colors.xml` files
- [ ] Looks right in **light and dark**, with **high contrast** and **bold text** on
- [ ] Readable at **Largest** text size: nothing clipped or overlapping
- [ ] Nothing animates when **reduce motion** is on
- [ ] TalkBack reads every control, headings are headings, decoration is skipped
- [ ] Personal data is marked with `PrivacyBlur`
- [ ] Loading, empty and error states are handled
- [ ] Screenshots in `docs/screenshots` and the [user guide](USER_GUIDE.md) updated if the screen changed
