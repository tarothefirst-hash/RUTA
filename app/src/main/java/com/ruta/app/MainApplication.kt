package com.ruta.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.ruta.app.util.FareConfig
import com.ruta.app.util.FareRates

class MainApplication : Application() {

    companion object {
        @Volatile
        private var startedActivities = 0
        /** True while any RUTA screen is visible (used to skip pop-ups the user would see anyway). */
        val inForeground: Boolean get() = startedActivities > 0
    }

    private var fareListening = false

    override fun onCreate() {
        super.onCreate()
        // Force light mode globally across the entire app
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) { if (startedActivities > 0) startedActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Fare rates the admin can edit. Listening starts once someone is signed in
        // (the database rules need auth) and stays attached for the life of the process.
        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            if (auth.currentUser != null && !fareListening) {
                fareListening = true
                FirebaseDatabase.getInstance().reference.child("config").child("fare")
                    .addValueEventListener(object : ValueEventListener {
                        override fun onDataChange(snapshot: DataSnapshot) {
                            FareConfig.update(FareRates.fromMap(snapshot.value as? Map<*, *>))
                        }
                        override fun onCancelled(error: DatabaseError) {
                            fareListening = false
                        }
                    })
            }
        }
    }
}
