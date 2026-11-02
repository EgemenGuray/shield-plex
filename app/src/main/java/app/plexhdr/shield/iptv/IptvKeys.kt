package app.plexhdr.shield.iptv

/**
 * Optional Live TV sources added on first launch, so nothing has to be typed with the remote. Leave empty to add them in
 * Live TV → Playlists instead. To keep your logins out of git after filling this in: `git update-index --skip-worktree`.
 *
 * Examples:
 *   Playlist("", "My TV", Playlist.Kind.Xtream, "http://provider.example:8080", username = "user", password = "pass")
 *   Playlist("", "Free channels", Playlist.Kind.M3U, "https://example.com/playlist.m3u", epgUrl = "https://example.com/guide.xml.gz")
 */
object IptvKeys {
    val PLAYLISTS: List<Playlist> = emptyList()
}
