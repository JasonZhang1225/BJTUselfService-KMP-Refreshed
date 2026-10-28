package team.bjtuss.bjtuselfservice.shared.media

actual fun defaultVideoCompositor(): VideoCompositor = AppleVideoCompositor

actual fun defaultPlaybackBackgroundHost(): PlaybackBackgroundHost? = IosPlaybackBackground
