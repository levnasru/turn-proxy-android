package com.freeturn.app.di

import com.freeturn.app.data.AppPreferences
import com.freeturn.app.domain.backup.BackupManager
import com.freeturn.app.domain.update.AppUpdater
import com.freeturn.app.domain.share.LinkImportBus
import com.freeturn.app.domain.subscription.XraySubscriptionFetcher
import com.freeturn.app.domain.proxy.LocalProxyManager
import com.freeturn.app.domain.proxy.ProxyOrchestrator
import com.freeturn.app.domain.proxy.ProxyServiceLauncher
import com.freeturn.app.service.AndroidProxyServiceLauncher
import com.freeturn.app.service.reality.RealityStateBridge
import com.freeturn.app.viewmodel.share.ImportViewModel
import com.freeturn.app.viewmodel.proxy.ProxyViewModel
import com.freeturn.app.viewmodel.settings.SettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val appModule = module {
    single { AppPreferences(androidContext()) }
    single { RealityStateBridge(androidContext()) }
    single<ProxyServiceLauncher> { AndroidProxyServiceLauncher(androidContext(), get(), get()) }
    single { LocalProxyManager(get()) }
    single { AppUpdater(androidContext()) }
    single { BackupManager(get()) }
    single { ProxyOrchestrator(get(), get()) }
    single { LinkImportBus() }
    single { XraySubscriptionFetcher() }

    viewModelOf(::ProxyViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::ImportViewModel)
}
