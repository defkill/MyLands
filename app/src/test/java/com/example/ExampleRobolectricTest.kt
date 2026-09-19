package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Пример Robolectric-теста.
 * 
 * ВАЖНО: Каждый новый файл с @RunWith(RobolectricTestRunner::class) в app/src/test/
 * обязан также иметь аннотацию @Config(sdk = [33]) — без неё сборка падает на этапе
 * инициализации класса с UnsupportedOperationException из DefaultSdkProvider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Ориентирование", appName)
  }
}
