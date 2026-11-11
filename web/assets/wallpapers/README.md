# Фоны

Эта папка заполняется на вашем компьютере и в git не попадает (фото принадлежат их авторам).

- `python3 -I tools/aerials.py` скачивает 16 снимков из заставки Apple TV Aerial с сервера Apple и пишет `list.js`.
- Без них у лаунчера остаются встроенные градиенты.

## Свои фото

1. Положите сюда JPEG 1920×1080 с коротким именем без пробелов, например `my-mountains.jpg`.
2. По желанию — уменьшенные копии `thumb/my-mountains.jpg` (480×270) и `preview/my-mountains.jpg` (720×405);
   на macOS: `sips --resampleHeightWidth 270 480 my-mountains.jpg --out thumb/my-mountains.jpg`.
   Без них добавьте в запись `"thumb": false`, лаунчер сделает копии сам.
3. Добавьте запись в начало массива в `list.js`:

```js
window.TVWallpapers = [
{ "id": "my-mountains", "name": "Горы", "place": "Моё фото" },
...
];
```

4. Пересоберите и установите приложение (`tools/build.sh`, `tools/tv.sh install`). `tools/aerials.py`
   записи, добавленные вручную, сохраняет.
