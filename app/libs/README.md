# Ядро Xray

Сюда кладётся `libv2ray.aar` (сборка AndroidLibXrayLite: https://github.com/2dust/AndroidLibXrayLite, релизы или `gomobile bind`).
Без него проект не соберётся: `VpnCore.kt` обращается к `libv2ray.*`.

Файл большой (десятки МБ). Лимит GitHub на один файл — 100 МБ; если не влезает — Git LFS или скачивание в шаге workflow.
Если у вашей версии .aar другие имена методов — правится только `VpnCore.kt`.
