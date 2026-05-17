# Svara App - Fix Tasks

## TODO
- [x] Understand codebase
- [ ] Remove background from app icon (Svara_1flSFI.png)
- [ ] Rename PlayPix -> Svara in all string resources (values/*.xml)
- [ ] Fix genre screen empty (investigate root cause - code looks correct, may be OK)
- [ ] Fix favorites (Liked tab): upsert song into songs table when favoriting streaming song
- [ ] Fix buffer/jitter: increase ExoPlayer buffer values for slow internet
- [ ] Folders tab: in streaming-only mode, show appropriate message

## FINDINGS

### App Rename
- app_name in values/strings.xml = "playpix" -> "Svara"
- Many string files have PlayPix -> Svara
- All language variants (de, es, fr, ko, nb, ru) also need updating

### Genre Screen
- Code flow: GenreDetailVM -> musicRepository.getMusicByGenre(genre.name)
- getMusicByGenre() -> flatMapLatest mockGenresEnabledFlow -> searchSongsForGenre(genreName)
- mockGenresEnabledFlow defaults to false - OK
- genreName = genre.name e.g. "Bollywood" - not "unknown" - OK  
- calls streamingRepository.searchSongsForGenre("Bollywood") -> api.searchSongs("Bollywood")
- API base URL: https://jiosavan-api2.vercel.app/ - OK
- Logic looks correct. Issue might be that the flow in getMusicByGenre is a cold Flow<List<Song>>
  but GenreDetailVM calls .first() which should work
- POSSIBLE ISSUE: the genre.name passed might not match what API expects
  e.g. "Lofi" vs "lo fi" - need to ensure proper genre name mapping

### Favorites (Liked Tab)
- CORE BUG: getFavoriteSongsPaginated does INNER JOIN songs ON favorites.songId = songs.id
- Streaming songs are NOT in the songs table
- setFavoriteStatus() only writes to favorites table, not songs table
- FIX: In setFavoriteStatus(), also upsert the song into songs table

### Folders Tab
- ENABLE_FOLDERS_STORAGE_FILTER = false -> uses OFFLINE filter -> returns empty
- In streaming mode there are no local files
- FIX: Either hide Folders tab or show "No folders in streaming mode" message

### Buffer/Jitter
- DualPlayerEngine: setBufferDurationsMs(30_000, 60_000, 5_000, 5_000)
- FIX: Increase to (60_000, 120_000, 2_500, 5_000) + add retry on error
  - minBuffer=60s, maxBuffer=120s, playbackBuffer=2.5s, rebuffer=5s
  - Also setWakeMode(C.WAKE_MODE_NETWORK) instead of WAKE_MODE_LOCAL

## DECISIONS
- Favorites: upsert streaming songs into DB when liked - matches user's intent
- Folders: Show "Folders not available in streaming mode" empty state
- Icon: use rembg to remove background
