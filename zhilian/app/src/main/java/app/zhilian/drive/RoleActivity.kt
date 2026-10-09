package app.zhilian.drive

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import app.zhilian.drive.car.CarActivity
import app.zhilian.drive.phone.PhoneActivity
import app.zhilian.drive.store.Vault

class RoleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vault = Vault(this)
        when (vault.role) {
            "car" -> {
                startActivity(Intent(this, CarActivity::class.java))
                finish()
                return
            }
            "phone" -> {
                startActivity(Intent(this, PhoneActivity::class.java))
                finish()
                return
            }
        }
        setContentView(R.layout.activity_role)
        findViewById<View>(R.id.carRole).setOnClickListener { choose("car", CarActivity::class.java) }
        findViewById<View>(R.id.phoneRole).setOnClickListener { choose("phone", PhoneActivity::class.java) }
    }

    private fun choose(role: String, cls: Class<*>) {
        Vault(this).role = role
        startActivity(Intent(this, cls))
        finish()
    }
}
