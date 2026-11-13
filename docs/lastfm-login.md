# Last.fm account login

Listeners connect with their Last.fm username and password. The app exchanges
these over HTTPS using `auth.getMobileSession`, then saves the returned session
in encrypted device storage. Passwords are held only by the login dialog and
are never stored. Existing scrobbling preferences and saved sessions are retained.

Release builders must supply the application's own `LASTFM_API_KEY` and
`LASTFM_SECRET`. Set both as build environment variables, Gradle properties,
or entries in untracked `local.properties`. Environment variables work with
CI release secrets. Never commit credential values. Developer credentials are
created at https://www.last.fm/api/account/create.

A build without credentials displays a configuration message instead of asking
listeners to create an API account. Existing installations with encrypted saved
credentials can still reconnect. Authentication and error handling are tested
with protocol fixtures; a live sign-in requires configured release credentials.
