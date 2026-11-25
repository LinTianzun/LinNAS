package com.linnas.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemePreferencesTest {
    @Test
    fun themesAndLastTabPersistAndInvalidValuesFallBack() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("linnas_ui", Context.MODE_PRIVATE).edit().clear().commit()
        try {
            ThemeStore.all.indices.forEach { index ->
                ThemeStore.setIndex(context, index)
                assertEquals(index, ThemeStore.index(context))
            }
            ThemeStore.setIndex(context, Int.MAX_VALUE)
            assertEquals(ThemeStore.all.lastIndex, ThemeStore.index(context))

            UiPrefs.setLastTab(context, R.id.tab_transfers)
            assertEquals(R.id.tab_transfers, UiPrefs.lastTab(context))
            UiPrefs.setLastTab(context, Int.MAX_VALUE)
            assertEquals(R.id.tab_transfers, UiPrefs.lastTab(context))
            context.getSharedPreferences("linnas_ui", Context.MODE_PRIVATE)
                .edit().putInt("last_tab", Int.MIN_VALUE).commit()
            assertEquals(R.id.tab_files, UiPrefs.lastTab(context))
        } finally {
            context.getSharedPreferences("linnas_ui", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
