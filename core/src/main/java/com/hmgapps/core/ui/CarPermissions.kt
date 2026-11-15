package com.hmgapps.core.ui

import android.content.Context

/**
 * Pleos Vehicle SDK 권한은 모두 normal(설치 시 자동 허용)이라 런타임 요청이 필요 없다.
 * 호출부 호환을 위해 남겨 둔다.
 */
object CarPermissions {
    @Suppress("UNUSED_PARAMETER")
    fun missingEnergy(context: Context): Array<String> = emptyArray()
}
