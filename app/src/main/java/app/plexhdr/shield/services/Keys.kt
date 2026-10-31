package app.plexhdr.shield.services

/**
 * Optional build-time API keys, so you don't have to type them with the remote. All free.
 * Leave empty to enter them in Settings → Online services instead.
 * To keep your keys out of git after filling them in: `git update-index --skip-worktree` on this file.
 */
object BuildKeys {
    const val TMDB = ""                    // themoviedb.org → Settings → API (v3 key or v4 read token)
    const val OMDB = ""                    // omdbapi.com → API Key
    const val OPENSUBTITLES = ""           // opensubtitles.com → Profile → API consumers
    const val OPENSUBTITLES_USER = ""
    const val OPENSUBTITLES_PASSWORD = ""
}
