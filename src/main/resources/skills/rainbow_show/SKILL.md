---
name: "rainbow_show"
description: "Animate the drone LED through a rainbow using the static set_led primitive and the hsvToRgb helper. Purely cosmetic."
tags: "rainbow led color light fun show music"
triggers:
  - "rainbow lights"
  - "led show"
  - "change colors"
  - "do a light show"
requires: []
version: 2
uses: 0
code: code.js
---

# Rainbow show

Cosmetic LED rainbow using the static `set_led` primitive and `hsvToRgb`.

## Parameters

- `steps` - number of colours 6..120. Default 36.
- `delay_ms` - delay between colours 0..1000. Default 120.

## Returns

`{ ok, steps }`

## Notes

- Runs inside one short script; long shows should be split.
