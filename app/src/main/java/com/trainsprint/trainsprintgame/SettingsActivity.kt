package com.trainsprint.trainsprintgame

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.trainsprint.trainsprintgame.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var prefs: GamePrefs
    private lateinit var sound: SoundManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = GamePrefs(this)
        sound = SoundManager(this, prefs)

        b.swSound.isChecked = prefs.soundEnabled
        b.swSound.setOnCheckedChangeListener { _, checked ->
            prefs.soundEnabled = checked
            if (checked) sound.play(R.raw.button_click_asset)
        }

        b.btnPrivacy.setOnClickListener {
            sound.play(R.raw.button_click_asset)
            openWeb(WebActivity.TYPE_PRIVACY)
        }
        b.btnSupport.setOnClickListener {
            sound.play(R.raw.button_click_asset)
            openWeb(WebActivity.TYPE_SUPPORT)
        }
        b.btnReset.setOnClickListener {
            sound.play(R.raw.achievement_unlock_asset)
            prefs.resetBalance()
            Toast.makeText(this, R.string.credits_reset, Toast.LENGTH_SHORT).show()
        }
        b.btnBack.setOnClickListener {
            sound.play(R.raw.menu_close_asset)
            finish()
        }
    }

    private fun openWeb(type: String) {
        startActivity(Intent(this, WebActivity::class.java).putExtra(WebActivity.EXTRA_TYPE, type))
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, b.root)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onDestroy() {
        super.onDestroy()
        sound.release()
    }
}
