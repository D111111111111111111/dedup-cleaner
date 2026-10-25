# Dedup Cleaner

Aplicación Android para detectar y eliminar archivos duplicados (fotos, videos) de la galería del dispositivo.

## Características

- **Duplicados exactos**: comparación por hash MD5 del contenido completo del archivo (agrupación previa por tamaño para rapidez).
- **Archivos parecidos**: hash perceptual dHash de 64 bits para imágenes; para videos se extraen **6 fotogramas** equidistantes con `MediaMetadataRetriever` y se comparan fotograma a fotograma.
- **Umbrales de similitud seleccionables**: 70 %, 80 % o 90 %.
- **Miniaturas** de cada archivo (via `loadThumbnail`).
- **Metadatos visibles**: nombre, fecha, tamaño y duración (videos).
- **Diseño**: grupos de 2 archivos se muestran lado a lado (izquierda/derecha); grupos de 3 o más se muestran en cuadrícula.
- **Preselección**: en duplicados exactos se preselecciona para eliminar todo salvo el archivo más reciente (editable manualmente). En parecidos la selección es manual.
- **Borrado seguro**: en Android 11+ se usa `MediaStore.createDeleteRequest` (el usuario confirma en el sistema).

## Compilar

1. **GitHub Actions**: al subir a `main` se ejecuta el workflow `.github/workflows/android.yml`, que compila el APK y lo publica como artefacto descargable (*Build APK* → *dedup-cleaner-debug-apk*).
2. **Local**: requiere JDK 17 y Gradle 8.7+ (`gradle assembleDebug`).

## Permisos

- Android 13+: `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO`
- Android 12 o inferior: `READ_EXTERNAL_STORAGE`

## Estructura del código

- `MainActivity.kt` — UI, permisos, escaneo y borrado
- `DuplicateScanner.kt` — lectura de MediaStore, hash MD5, agrupación exacta y clustering de parecidos (union-find)
- `Hashing.kt` — MD5, dHash, extracción de 6 fotogramas de video, similitud por distancia Hamming
- `GroupAdapter.kt` + layouts — grupos con cuadrícula de 2 columnas, miniaturas y casillas de selección

## Notas

- minSdk 29 (Android 10), targetSdk 34.
- El clustering de parecidos compara todos los pares (O(n²)); pensado para galerías de tamaño medio.