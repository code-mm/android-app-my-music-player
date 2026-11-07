package com.codemm.music.player

import android.app.Application

class MusicApplication : Application() {
    val library: MusicLibrary by lazy { MusicLibrary(this) }
}
