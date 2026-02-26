package com.mynewtime.app

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import android.view.LayoutInflater

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val container = findViewById(R.id.fragment_container) as FrameLayout
        val btnFeed = findViewById(R.id.nav_feed) as Button
        val btnProfile = findViewById(R.id.nav_profile) as Button
        val btnSearch = findViewById(R.id.nav_search) as Button

        loadProfile(container)

        // ИСПРАВЛЕНИЕ: Заменяем лямбды { } на анонимные классы
        // Это уберет ошибку invokedynamic
        btnFeed.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                container.removeAllViews()
                Toast.makeText(applicationContext, "Feed selected", Toast.LENGTH_SHORT).show()
            }
        })

        btnProfile.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                loadProfile(container)
            }
        })
        
        btnSearch.setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View?) {
                Toast.makeText(applicationContext, "Search placeholder", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun loadProfile(container: FrameLayout) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val profileView = inflater.inflate(R.layout.layout_profile, container, false)
        container.addView(profileView)
    }
}
