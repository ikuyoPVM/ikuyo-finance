package xyz.ikuyo.finance

import android.app.Application
import xyz.ikuyo.finance.di.Graph

class IkuyoApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}