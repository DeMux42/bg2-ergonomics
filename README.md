<a id="readme-top"></a>

<!-- Structure adapted from othneildrew/Best-README-Template, credited in Acknowledgments. -->

[![Release][release-shield]][releases-url]
[![Minecraft][mc-shield]][mc-url]
[![NeoForge][neoforge-shield]][neoforge-url]
[![Building Gadgets 2][bg2-shield]][bg2-url]
[![Java][java-shield]][java-url]
[![License][license-shield]][license-url]

<br />
<div align="center">
  <h3 align="center">BG2 Ergonomics</h3>

  <p align="center">
    Clipboard history and easier controls for Building Gadgets 2.
    <br />
    <a href="https://github.com/DeMux42/bg2-ergonomics/releases"><strong>Download the latest release</strong></a>
    <br />
    <br />
    <a href="https://github.com/DeMux42/bg2-ergonomics/issues/new?labels=bug">Report a bug</a>
    &middot;
    <a href="https://github.com/DeMux42/bg2-ergonomics/issues/new?labels=enhancement">Request a feature</a>
  </p>
</div>

<details>
  <summary>Table of contents</summary>
  <ol>
    <li><a href="#about-the-project">About the project</a>
      <ul>
        <li><a href="#built-with">Built with</a></li>
      </ul>
    </li>
    <li><a href="#getting-started">Getting started</a>
      <ul>
        <li><a href="#prerequisites">Prerequisites</a></li>
        <li><a href="#installation">Installation</a></li>
      </ul>
    </li>
    <li><a href="#usage">Usage</a>
      <ul>
        <li><a href="#scroll-controls">Scroll controls</a></li>
        <li><a href="#controls-overlay">Controls overlay</a></li>
        <li><a href="#clipboard-history">Clipboard history</a></li>
        <li><a href="#mirror-and-flip">Mirror and Flip</a></li>
      </ul>
    </li>
    <li><a href="#multiplayer">Multiplayer</a></li>
    <li><a href="#configuration">Configuration</a></li>
    <li><a href="#building-from-source">Building from source</a></li>
    <li><a href="#known-limits">Known limits</a></li>
    <li><a href="#roadmap">Roadmap</a></li>
    <li><a href="#contributing">Contributing</a></li>
    <li><a href="#license">License</a></li>
    <li><a href="#contact">Contact</a></li>
    <li><a href="#acknowledgments">Acknowledgments</a></li>
  </ol>
</details>

## About the project

BG2 Ergonomics is an add-on for [Building Gadgets 2][bg2-url] on Minecraft 1.21.1 and NeoForge. It makes the gadgets quicker to use. You hold a key and scroll to stretch, slide or rotate what you're building, and you pick a recent copy from a clipboard history by its picture.

It runs alongside BG2 and doesn't replace BG2's files. It reads and writes BG2's own data and keeps its own state in its own world data, so removing it leaves BG2 items and worlds untouched. Three small patches go into BG2 itself. Each one is optional, so if a BG2 update breaks one, the game logs it and keeps BG2's own behaviour.

What it adds:

- **Scroll-wheel controls** for every gadget. They act on the side of the selection or paste you point toward, so what a scroll does never depends on which way you face.
- **A controls overlay** on the left of the screen, listing what each key does with the gadget you hold.
- **Clipboard history** for the Copy/Paste gadget, with a picture of each recent copy.
- **Mirror and Flip** for Copy/Paste and Cut/Paste copies.
- **Copies keep doors, trapdoors and fence gates open.** Plain BG2 resets them, so an open trapdoor pastes back closed ([BG2 issue #175](https://github.com/Direwolf20-MC/BuildingGadgets2/issues/175)).
- **Template Manager preview fixes.** The preview is built once per template instead of on every frame, turns around the template's centre and backs off far enough to show all of it, and counts every zoom step. It also checks for a block that has no block entity, where BG2 crashes ([BG2 issue #219](https://github.com/Direwolf20-MC/BuildingGadgets2/issues/219)).

**0.1.0-alpha.1 is the first public test build.** Expect rough edges, and back up any world you care about before you use it.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

### Built with

- Java 21.
- [NeoForge](https://neoforged.net/) 21.1.77 for Minecraft 1.21.1, built with ModDevGradle 2.0.141 and [Parchment](https://parchmentmc.org/) mappings 2024.11.17.
- Mixin, through NeoForge, for the three optional BG2 patches.
- [Building Gadgets 2][bg2-url] 1.3.9 and Curios 9.5.1, pulled from [CurseMaven](https://cursemaven.com) at build time.
- Gradle 8.10.2, through the wrapper.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Getting started

### Prerequisites

- Minecraft 1.21.1
- NeoForge for 1.21.1, version 21.1 or newer
- [Building Gadgets 2][bg2-url] 1.3.9 or a later 1.3 release, and [Curios](https://www.curseforge.com/minecraft/mc-mods/curios), which BG2 needs. The add-on reads BG2 internals, so it's pinned to the 1.3 line.

Building Gadgets Extra is optional. With it installed, the Copy/Paste size limit follows Extra's `maxCopyVolume` server setting, and the add-on hides its own Mirror and Flip buttons, since Extra has its own.

### Installation

1. Download `bg2ergonomics-0.1.0-alpha.1.jar` from the [releases page][releases-url]. The release notes list its SHA-256.
2. Put it in your `mods` folder, next to Building Gadgets 2.
3. On a multiplayer server, put it in the server's `mods` folder too. The clipboard history, Mirror and Flip, and open doors in copies need it there. See [Multiplayer](#multiplayer).
4. Launch Minecraft, hold a gadget, hold <kbd>Ctrl</kbd> and scroll. The overlay on the left shows the rest.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Usage

Every key is in Options, Controls, under Building Gadgets 2, and you can rebind any of them. The controls only act while you hold a gadget and one of their keys, so plain scrolling still changes the hotbar slot.

### Scroll controls

| Gadget                                  | <kbd>Ctrl</kbd> + scroll                                                  | <kbd>Alt</kbd> + scroll                                             |
| --------------------------------------- | ------------------------------------------------------------------------- | ------------------------------------------------------------------- |
| Copy/Paste or Cut/Paste, in Copy or Cut | Stretch the side you point toward                                         | Slide the whole selection toward that side                          |
| Copy/Paste or Cut/Paste, in Paste       | Rotate 90°, either way                                                    | Slide the paste toward that side. The first slide pins it in place  |
| Building or Exchanging                  | Range, from 1 to 15                                                       | Nothing, so the hotbar scrolls                                      |
| Destruction                             | Size of the edge under the crosshair. From the face's centre, the depth   | Depth                                                               |

- <kbd>Ctrl</kbd> + <kbd>Alt</kbd> + scroll on a selection stretches the opposite side, for sides you can't see.
- Hold <kbd>X</kbd> while scrolling for steps of 10. It isn't Shift, because Shift descends while flying.
- Wheel up always moves the side outward.
- BG2's anchor key, <kbd>H</kbd>, unpins a paste. It then follows the crosshair again, with the offset it had before.

Picking a side works like a radial menu laid over the box. Point at the middle of a side to pick it. Point near an edge, or just past the box, to pick the side beyond that edge, such as the top, the back, or a side you see edge-on. The side stays picked while you hold the key, so it can leave the crosshair.

The picked side lights up yellow when the wheel stretches it and blue when the wheel slides toward it, and it flashes on each step. The selection outline moves with the wheel instead of waiting for the server. The action bar shows the new size and block count.

A selection stops growing at BG2's copy limits. Its corners can be at most 500 blocks apart on each axis, and the box can hold at most 100,000 blocks. With Building Gadgets Extra, the Copy/Paste block limit is Extra's `maxCopyVolume`.

### Controls overlay

The held gadget's controls show on the left edge of the screen, with key names from your bindings. The row for the keys you're holding lights up in the colour of its highlight. The overlay hides with <kbd>F1</kbd>, <kbd>F3</kbd> and any open screen. To turn it off, untick **Show scroll controls** in the gadget's mode menu (<kbd>G</kbd>).

### Clipboard history

The Copy/Paste gadget keeps your recent copies. A copy is saved when you start using it, by switching to Paste mode or by opening the clipboard. Adjusting a selection in Copy mode doesn't save each step, and rotating a paste doesn't add an entry. The Cut/Paste gadget has no history on purpose. Its data is a move, and replaying it would duplicate blocks.

| Key                                                                         | Action                                                                   |
| --------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| <kbd>V</kbd>, tapped                                                        | Open the clipboard, a grid of pictures with the newest first             |
| <kbd>V</kbd>, held, then scroll                                             | Flip through copies without opening it. Wheel down goes older            |
| Scroll or arrow keys, in the clipboard                                      | Load a copy into the gadget, so the paste preview in the world shows it  |
| <kbd>1</kbd> to <kbd>9</kbd>, <kbd>Enter</kbd>, a click, or <kbd>V</kbd>    | Keep the selected copy and close                                         |
| <kbd>Page Up</kbd>, <kbd>Page Down</kbd>, <kbd>Home</kbd>, <kbd>End</kbd>   | Move a page, or to either end                                            |
| <kbd>Del</kbd>                                                              | Remove the selected copy                                                 |
| <kbd>Esc</kbd>                                                              | Go back to what the gadget held before                                   |

**Clear All** asks once before it empties the history. The mode menu also has a **Clipboard** button.

Each player keeps 20 copies by default, and the oldest drops off when the history is full. Players can load only their own copies. Copies that are all air aren't saved. The history is saved with the world, in `data/bg2ergonomics_clipboard.dat`, so it survives a restart.

### Mirror and Flip

Mirror flips the held Copy/Paste or Cut/Paste copy left to right, as you face it. Flip turns it upside down. Both are buttons under the gadget's mode menu (<kbd>G</kbd>), beside **Clipboard** on the Copy/Paste gadget. Both also have keybinds, unbound by default so they take no key from anything else.

The copy flips in place, so the paste keeps its footprint. Flip also turns blocks that have an up or a down, such as stairs, slabs, trapdoors and buttons. Doors and tall plants stay as they are, because BG2 copies both of their halves as the lower one.

With Building Gadgets Extra installed, these buttons are hidden, since Extra has its own.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Multiplayer

Install the add-on on each client. On the server it's optional. A client with it can join a server without it, and the other way round.

| Feature                                           | Needs the add-on on the server |
| ------------------------------------------------- | ------------------------------ |
| Scroll controls, the overlay and the outline      | No, they use BG2's own packets |
| Template Manager preview fixes                    | No                             |
| Clipboard history and its pictures                | Yes                            |
| Mirror and Flip                                   | Yes                            |
| Doors, trapdoors and gates staying open in copies | Yes                            |

Without the add-on on the server, the clipboard and Mirror keys say so in the action bar.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Configuration

| Setting           | Config | Default            | What it does                                                                     |
| ----------------- | ------ | ------------------ | -------------------------------------------------------------------------------- |
| `clipboardSize`   | Server | `20`, from 0 to 100 | How many copies each player's history keeps. `0` turns the history off          |
| `controlsOverlay` | Client | `true`             | Shows the controls overlay. The checkbox in the gadget's mode menu sets it too   |

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Building from source

```sh
git clone https://github.com/DeMux42/bg2-ergonomics.git
cd bg2-ergonomics
./gradlew build
```

Requires JDK 21. The jar lands in `build/libs` as `bg2ergonomics-<version>.jar`. Building Gadgets 2 and Curios resolve from CurseMaven.

`./gradlew runClient` starts a dev client with BG2 and Curios loaded. `./gradlew runGameTestServer` runs the add-on's GameTests and exits non-zero if one fails.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Known limits

- Clipboard pictures leave out water and lava. Blocks with special renderers, such as signs and some modded machines, show as their plain model.
- In Controls, Ctrl shows red next to Sprint. Both still work.
- It works with Building Gadgets 2 1.3.x only, because it reads BG2 internals.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Roadmap

0.1.0-alpha.1, the first public test build, came out in October 2026. Everything below is in it.

- [x] Scroll-wheel controls for every gadget, with sides picked like a radial menu
- [x] Controls overlay
- [x] Clipboard history with pictures, for the Copy/Paste gadget
- [x] Mirror and Flip
- [x] Copies keep doors, trapdoors and fence gates open
- [x] Template Manager preview fixes
- [x] GameTests for the clipboard and for the math behind the controls

See the [issues](https://github.com/DeMux42/bg2-ergonomics/issues) for anything reported since.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Contributing

Issues and pull requests are welcome.

1. Fork the repository
2. Create a branch (`git checkout -b feature/thing`)
3. Commit your changes
4. Push and open a pull request

Run the GameTests before you open a PR:

```sh
./gradlew runGameTestServer
```

They drive the clipboard against real Building Gadgets 2 world data, and check the math behind the controls, mirroring and the preview camera. Input handling and rendering are client only, so check those in game with `./gradlew runClient`.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## License

MIT. See [`LICENSE`](LICENSE).

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Contact

DeMux - [@DeMux42](https://github.com/DeMux42)

Project link: [https://github.com/DeMux42/bg2-ergonomics](https://github.com/DeMux42/bg2-ergonomics)

<p align="right">(<a href="#readme-top">back to top</a>)</p>

## Acknowledgments

- [Building Gadgets 2][bg2-url] by Direwolf20, which this add-on builds on.
- [NeoForge](https://neoforged.net/) and [Parchment](https://parchmentmc.org/).
- [CurseMaven](https://cursemaven.com), for Building Gadgets 2 and Curios in the build.
- [Best-README-Template](https://github.com/othneildrew/Best-README-Template) by othneildrew, for this README's structure.
- [Shields.io](https://shields.io) for the badges.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

[release-shield]: https://img.shields.io/github/v/release/DeMux42/bg2-ergonomics?include_prereleases&sort=semver&style=for-the-badge
[releases-url]: https://github.com/DeMux42/bg2-ergonomics/releases
[mc-shield]: https://img.shields.io/badge/minecraft-1.21.1-62B47A?style=for-the-badge
[mc-url]: https://www.minecraft.net/
[neoforge-shield]: https://img.shields.io/badge/neoforge-21.1-E68C37?style=for-the-badge
[neoforge-url]: https://neoforged.net/
[bg2-shield]: https://img.shields.io/badge/building_gadgets_2-1.3.x-4A90D9?style=for-the-badge
[bg2-url]: https://www.curseforge.com/minecraft/mc-mods/building-gadgets
[java-shield]: https://img.shields.io/badge/java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white
[java-url]: https://adoptium.net/
[license-shield]: https://img.shields.io/badge/license-MIT-green?style=for-the-badge
[license-url]: LICENSE
