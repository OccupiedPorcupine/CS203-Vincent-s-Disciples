# Third-party notices

Chicky's motion layer (see `js/motion.js`, `css/base.css`) uses two open-source projects, and adapts the idea of a third. This
file keeps their notices with the code that uses them.

## GSAP: no longer used

GSAP and ScrollTrigger were used briefly for the Record sales dialog's entrance and scroll reveals. Both were replaced
(the dialog's entrance is a CSS keyframe, and pages arrive complete), and no page loads GSAP any more. Nothing of it is
bundled or copied.

## Lenis: loaded from a CDN, not copied

- Version 1.3.27, pinned with an integrity hash in the `<head>` of every signed-in page
  (`https://cdn.jsdelivr.net/npm/lenis@1.3.27/dist/lenis.min.js`): `dashboard`, `forecast`, `prep`, `data`, `dishes`,
  `documents` and `account`. The sign-in page does not load it (Lenis disables pointer events on iframes, and Google's
  sign-in button is one).
- Source: <https://github.com/darkroomengineering/lenis>. Licence: MIT, © darkroom.engineering.
- The few rules from its recommended stylesheet (`lenis.css`) are repeated near the top of
  `css/base.css`, which is permitted by the MIT licence; this notice is their attribution.
- Used for: gentle smooth scrolling with a mouse wheel on desktop only, from one shared set-up in `js/motion.js`
  (one instance, `autoRaf`). Touch, reduced motion, a running guided tour and a failed load all keep native scrolling.

## React Bits: vanilla adaptations, not the React components

- Source: <https://github.com/DavidHDev/react-bits>. Licence: **MIT + Commons Clause**, © 2026 David Haz.
- Nothing from React Bits is installed and React is not used. One effect was **re-implemented in plain
  JavaScript and CSS** after reading the current source of the component below. It is an adaptation
  of the idea and behaviour, not a copy of the file. (A second, `AnimatedContent`, was adapted earlier as a scroll
  reveal and has since been removed.)

  | React Bits component | Adapted as | Where |
  | --- | --- | --- |
  | `Components/SpotlightCard` (pointer-following light and rim glow) | `[data-spotlight]` pointer light | `js/motion.js`, `css/base.css` |

- The licence lets the components be used "as part of an application, website, or product" provided the
  copyright and permission notice is kept, and forbids selling, sublicensing or redistributing the
  components themselves, "alone, in a bundle, or as a ported version". These adaptations live inside
  Chicky only. **Do not publish `js/motion.js`'s spotlight code, or this port, as a standalone
  library or component pack.**

The React Bits notice, reproduced as the licence requires:

> MIT + Commons Clause License Condition v1.0
>
> Copyright (c) 2026 David Haz
>
> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
> associated documentation files (the "Software"), to deal in the Software without restriction, including
> without limitation the rights to use, copy, modify, merge, publish, and distribute the Software as part
> of an application, website, or product, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial
> portions of the Software.
>
> You may use this Software, including for any commercial purpose, so long as you do not sell, sublicense,
> or redistribute the components themselves, whether alone, in a bundle, or as a ported version.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
> LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
> EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
> IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
> THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(The full licence text, including the clauses about the React Bits tools and sound library, is at
<https://github.com/DavidHDev/react-bits/blob/main/LICENSE.md>. That part of it does not apply here
because none of those tools or sounds are used.)
