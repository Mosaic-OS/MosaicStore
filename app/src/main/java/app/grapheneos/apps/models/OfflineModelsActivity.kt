/*
 * Copyright (C) 2026 The MosaicOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.grapheneos.apps.models

import android.os.Bundle
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import app.grapheneos.apps.R
import app.grapheneos.apps.databinding.OfflineModelsActivityBinding

class OfflineModelsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val views = OfflineModelsActivityBinding.inflate(layoutInflater)

        enableEdgeToEdge()

        ViewCompat.setOnApplyWindowInsetsListener(views.root) { v, insets ->
            val paddingInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = paddingInsets.left
                rightMargin = paddingInsets.right
            }
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(views.toolbar) { v, insets ->
            val paddingInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = paddingInsets.top
            }
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(views.modelsContent) { v, insets ->
            val paddingInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, paddingInsets.bottom)
            insets
        }

        setContentView(views.root)
        setSupportActionBar(views.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.models_title)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.models_content, OfflineModelsFragment())
                .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
