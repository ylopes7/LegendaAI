# LegendaAI

Aplicativo Android experimental para reconhecer legendas já impressas na imagem do vídeo usando OCR e traduzi-las do inglês para português brasileiro em uma sobreposição.

## Estado atual
Protótipo inicial. Ainda precisa ser compilado e testado em aparelho Android real. A captura contínua usa MediaProjection e OCR; DRM pode impedir a captura. A sobreposição ainda não apaga perfeitamente os pixels da legenda original.

## Build
O GitHub Actions está configurado para gerar um APK debug em push para `main` ou por execução manual (`workflow_dispatch`).
