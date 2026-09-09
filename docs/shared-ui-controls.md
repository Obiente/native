# Shared native choice controls

`app/design/NextcloudSegmentedControl.kt` and `NextcloudChoiceField.kt` live in
`commonMain`. Phone and desktop use the same selection and keyboard behavior.
The caller supplies stable IDs, labels, current selection, availability and a
callback. Neither component interprets a schema, saves data or grants permission.

## Choose the right control

| Need | Component | Current consumers |
| --- | --- | --- |
| Switch between a few views | `NextcloudSegmentedControl`, tab role | Calendar Month/Week/Agenda; compact Chores navigation |
| Choose one local filter | `NextcloudSegmentedControl`, radio-button role | Budget category All/Expenses/Income |
| Choose a value in a form | `NextcloudChoiceField` | Calendar recurrence and calendar; dynamic enum fields |

Multi-selection, hierarchical navigation and actions are different interactions.
Do not replace their controls just because they have similar rounded shapes.
Desktop Chores keeps its sidebar. Calendar keeps its date grid, event rows and
calendar-visibility controls because those express Calendar-specific behavior.

## View and filter selection

- Keep option IDs unique and stable. Labels and counts may change independently.
- Selection is hoisted. Removing or reordering options never chooses a fallback
  or invokes a callback. The caller decides how to handle an unknown selection.
- Activating the selected option is a no-op. Disabled options cannot activate.
- Left/Right move focus without switching views. Home/End move to the first/last
  enabled option. Enter/Space activates. Horizontal movement respects RTL.
- Each option has a minimum 48dp target and a visible keyboard focus outline.
- Labels retain their full width. Bounded controls scroll and expose an options
  menu when they overflow. A control inside a scrolling toolbar uses natural
  width instead of nesting another unbounded horizontal scroller.
- Resizing and reordering reveal the focused option, or the selected option when
  focus is elsewhere. Revealing an option does not select it or steal focus.

## Form choices

The field shows a label, selected value, optional leading icon or swatch, and
an adjacent error message. Disabled fields have muted text. Keyboard focus has
a visible outline. Unknown IDs remain visible; the field does not silently
replace them with the first available choice.

The menu is bounded to its anchor width up to 420dp and a maximum 360dp height.
It scrolls on short screens. Lists longer than eight choices include search,
limited to 120 characters. Search matches IDs, labels and caller-supplied aliases.
The selected row has a check indicator. Unavailable options remain disabled.

Dynamic forms preserve exact wire values, required labels, validation errors,
icon/color previews and existing automation descriptions. Calendar passes
recurrence preset names and calendar hrefs. Each caller still owns pending-save
state, dirty-draft guards, validation and server authorization.

## Validation

Common tests cover focus-target resolution and search. Native Compose scene
tests cover pointer and keyboard activation, disabled states, stable IDs,
resizing, reordering, overflow, RTL and 320dp layouts with increased font size.
Consumer tests exercise Chores navigation guards and real dynamic enum/category
renderers. Calendar interaction tests continue to cover both adaptive layouts.

Visual QA includes `shared-controls-desktop` and `shared-controls-mobile` in both
themes, alongside the actual Calendar, Chores, Budget and record-editor captures.
These are deterministic native renders with synthetic data. They do not replace
physical-device, IME, touchpad or screen-reader acceptance checks.
