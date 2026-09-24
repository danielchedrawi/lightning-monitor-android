# Lightning Monitor Android

Proyecto limpio para SFL8 y SFL1 usando directamente los enlaces Kepler51.
Consulta el contenido de cada WebView cada 5 segundos.

OPEN = normal
WARNING = advertencia sin alarma
CLOSED = activa alarma y registra hora

IMPORTANTE: antes de usarlo como sistema de seguridad, hay que validar con un evento real que CLOSED corresponde al evento de lightning que queremos detectar.


## UI 1.3
- Kepler is no longer shown in the app UI.
- SFL8 and SFL1 are the primary station controls.
- Each station has a compact lightning-map button; the inactive station button is disabled and dimmed.
- The visible area is reserved for the lightning map.
- A hidden Kepler WebView continues monitoring the selected station for CLOSED alerts.
- Notification channel was moved to `lightning_alerts_v2` so Android can create the corrected alert channel.
