---
colors:
  paper-light: "#F5F2E9"
  ink-light: "#242632"
  signal-light: "#283CA9"
  paper-dark: "#171922"
  ink-dark: "#F0EEE7"
  signal-dark: "#BFC7FF"
typography:
  title:
    fontFamily: "CozetteVector"
    fontSize: 32sp
  section:
    fontFamily: "CozetteVector"
    fontSize: 23sp
  body:
    fontFamily: "CozetteVector"
    fontSize: 18sp
  detail:
    fontFamily: "CozetteVector"
    fontSize: 15sp
spacing:
  edge: 20dp
  section: 24dp
  control: 52dp
---

English · [Русский](DESIGN.ru.md)

## Overview

The connection screen takes cues from a measuring instrument. Connection status and the main action share one block, with data in flat rows below. Three primary tabs sit at the bottom.

## Colors

The background and text establish contrast for each theme. The accent marks the primary action, the selected tab, and chart lines. Dividers use a translucent version of the text color.

## Typography

CozetteVector is used for Cyrillic, Latin characters, and numbers. Text sizes use sp to respect the Android font scale.

## Layout

The main screen starts with the connection status and default VPN node. It then shows live traffic rates, a ten-minute history, site route assignments, and a shortcut to the server switch log. The app list shows the selected mode, test status, and active server. Tapping a row opens the mode picker. Settings link to the server and subscription catalog, Quick Settings tile and notification controls, and the theme picker. The catalog uses flat rows. Each row opens the corresponding source form. The manual server form groups protocol, transport, and security settings. The save action is pinned at the bottom.

## Components

The ZIQENO mark is a stepped Z with two square cutouts. Its source is in `branding/ziqeno.svg`. The adaptive icon uses a light accent on a dark background. The widget, tile, and notification use a monochrome version of the mark.

Square corners, thin borders, and pixel-style icons. Step lines plot download and upload history. When history is empty, the chart shows a blank scale and a text label. Interactive elements have touch targets of at least 48 dp.
