# NoteblockAddons

Client-side Fabric mod that adds a music player. 
Should be able to pick up spotify, soundcloud, youtube etc. and sync with lyrics (sometimes) 
design is vibecoded (im ass at designing things) aswell as the powershell fix bc i got lazy :P

## Requirements

- A pc with windows
- Minecraft 26.2/26.2, Fabric Loader 0.19.3+, Fabric API
- [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)
- like 20mb of ram
- a Screen

## Commands

| Commands

| `/note` or `/note status`  Whats playing right now 
| `/note toggle`  Show or hide the HUD 
| `/note lyrics`  Show or hide lyrics  
| `/note edit`  Drag to move, scroll to resize, Esc to save 
| `/note reset`  Reset HUD position and size 

## What it does on your PC and online

- **Reads what's playing from Windows.** It asks Windows for the current media session (the same thing the volume-key popup shows) four times a second using Windows own media API (`WindowsMediaSource.java` , `WinRt.java`). It doesnt control your music or touch other programs (If you dont believe me read the code yourself).
- **Settings** are saved in `config/noteblockaddons.json`. :D
- **Lyrics** song info goes to LRCLIB to retrieve lyrics.

## Credits

Built with [Cascade](https://github.com/skies-starred/cascade) by Starred (BSD 3-Clause, bundled). Lyrics from [LRCLIB](https://lrclib.net). See `THIRD_PARTY_NOTICES.md` for licenses and other credits. 

## License

MIT, see `LICENSE`.
