# Berry Voice — BerryMC

Portal estático responsivo da BerryMC para orientar o pareamento do Bedrock Simple Voice.

## O que o site faz

- Apresenta o portal Berry Voice para jogadores Java e Bedrock.
- Aceita o código de pareamento de 8 caracteres exibido pelo comando `/voice`.
- Aceita letras e números; espaços e hífens usados apenas para separar o código são removidos.
- Copia o código limpo para a área de transferência e abre o cliente web oficial do Bedrock Simple Voice.
- O jogador cola o código no campo **Connection code** do cliente oficial e conclui o pareamento por lá.
- Não armazena o código, não solicita credenciais e não transmite áudio.

## Importante sobre a integração

Este projeto é um portal personalizado, não uma implementação do protocolo de áudio do Bedrock Simple Voice. A validação do código, a sessão de voz, o microfone e o áudio continuam no cliente oficial (`https://voice.byterider.xyz/`) e no relay configurado pelo plugin.

O site no GitHub Pages, sozinho, não consegue validar um código `/voice` nem substituir o relay. Por isso, o portal copia o código e abre o cliente oficial; o jogador ainda precisa colá-lo no cliente.

Para hospedar também o cliente de áudio sob um domínio próprio, será necessário configurar o modo Direct do plugin ou executar o Standalone Relay próprio. Isso é uma etapa de infraestrutura separada do GitHub Pages.

## Publicação

O repositório publica a pasta raiz (`main / (root)`) pelo GitHub Pages. O domínio personalizado configurado é:

`voice.berrymc.net.br`

O DNS deve conter um registro CNAME de `voice` apontando para `yadzdev.github.io`. O arquivo `CNAME` já está incluído.

**Nunca publique o `network.secret` do plugin neste repositório.** Ele deve permanecer apenas nas configurações privadas do proxy e dos backends.

## Arquivos

- `index.html` e `voz/index.html`: interface do portal.
- `styles.css`: cores, layout e responsividade.
- `app.js`: normalização, validação local do tamanho, cópia e abertura do cliente oficial.
- `assets/`: identidade visual da BerryMC.
