package dev.nosurprise.mobile

import com.codeborne.selenide.WebDriverProvider
import io.appium.java_client.android.AndroidDriver
import io.appium.java_client.android.options.UiAutomator2Options
import org.openqa.selenium.Capabilities
import org.openqa.selenium.WebDriver
import java.net.URL

class ChromeDriverProvider : WebDriverProvider {

    override fun createDriver(capabilities: Capabilities): WebDriver {
        val options = UiAutomator2Options().apply {
            merge(capabilities)
            setCapability("platformName", "Android")
            setCapability("automationName", "UiAutomator2")
            setCapability("deviceName", System.getenv("ANDROID_DEVICE") ?: "emulator-5554")
            setCapability("browserName", "Chrome")
            setCapability("appium:newCommandTimeout", 180)
            setCapability("appium:chromedriverAutodownload", true)
            System.getenv("CHROMEDRIVER_PATH")?.let {
                setCapability("appium:chromedriverExecutable", it)
            }
        }
        val url = System.getenv("APPIUM_URL") ?: "http://127.0.0.1:4723/"
        return AndroidDriver(URL(url), options)
    }
}
