# Assistente de Voz (Normacare)

Aplicativo Android pensado para facilitar o uso do celular por idosos que têm dificuldade com interfaces tradicionais.

A ideia é simples: reduzir ao máximo a complexidade e permitir que ações básicas sejam feitas por voz, de forma clara e segura.

---

## Sobre o projeto

Esse app funciona como um assistente básico que entende comandos simples e executa ações como:

- abrir o WhatsApp  
- acessar a internet  
- ligar para um contato  
- confirmar ou cancelar ações  

Tudo foi pensado para evitar menus complexos e depender o mínimo possível de interação manual.

---

## Como rodar o projeto

1. Abra o Android Studio  
2. Clique em "Open" e selecione a pasta do projeto  
3. Aguarde a sincronização do Gradle  
4. Conecte um celular (recomendado por causa do microfone)  
5. Execute o app  

---

## Permissões

Na primeira execução, o app pede algumas permissões essenciais:

- Microfone → para captar comandos de voz  
- Contatos → para encontrar pessoas pelo nome  
- Telefone → para realizar chamadas diretamente  

---

## Exemplos de uso

O funcionamento é baseado em comandos simples. Alguns exemplos:

- "abrir WhatsApp" ou "zap" → abre o WhatsApp  
- "abrir internet" ou "google" → abre o navegador  
- "ligar para João" → procura o contato e pede confirmação  
- "sim" → confirma a ação  
- "não" → cancela  

---

## Estrutura do projeto

O app é propositalmente simples:

- `MainActivity.kt` concentra praticamente toda a lógica  
- uma única tela (`activity_main.xml`)  
- estilos e cores pensados para alto contraste  
- uso direto de APIs nativas do Android (sem libs externas)

---

## Personalização

Se quiser adaptar o comportamento:

- idioma da voz → altere o `Locale` no TTS  
- novos comandos → adicione regras na normalização de texto  
- site padrão → troque a URL usada ao abrir o navegador  

---

## Decisões técnicas

Algumas escolhas foram feitas para manter o app estável e simples:

- mínimo Android 8 (API 26), onde o reconhecimento de voz é mais consistente  
- uso de fila no TTS para evitar sobreposição de falas  
- busca de contatos por nome parcial (não exige nome exato)  
- fallback de chamada (se não conseguir ligar direto, abre o discador)  

---

## Observação

Esse projeto é um MVP. A ideia principal aqui não é complexidade, e sim resolver um problema real com o menor atrito possível para o usuário.