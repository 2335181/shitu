package app.shitu.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object Search : NavKey

@Serializable
data object Tools : NavKey

@Serializable
data object Workspace : NavKey

@Serializable
data object Me : NavKey
