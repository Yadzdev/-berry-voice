# Berry Voice — BerryMC

Site estático responsivo para publicar no GitHub Pages em `https://voice.berrymc.net.br`.

## O que o site faz

- Apresenta o portal Berry Voice para Java e Bedrock.
- Recebe o código numérico gerado pelo comando `/voice` no Minecraft.
- Copia o código para a área de transferência e abre o cliente web oficial do Bedrock Simple Voice em uma nova aba.
- O jogador cola o código no campo **Connection code** do cliente oficial e conclui o pareamento por lá.
- Não armazena o código, não solicita credenciais e não transmite áudio.

## Importante sobre a integração

Este projeto é um portal personalizado, não uma implementação do protocolo de áudio do Bedrock Simple Voice. A validação do código, a sessão de voz, o microfone e o áudio continuam no cliente oficial (`https://voice.byterider.xyz/`) e no relay configurado pelo plugin.

O site no GitHub Pages, sozinho, não consegue validar um código `/voice` nem substituir o relay. Não há integração documentada de deep-link para preencher automaticamente o código no cliente; por isso, o portal copia o código e abre o cliente oficial sem enviar o código na URL.

Para colocar também o cliente de áudio sob um domínio próprio, será necessário configurar o modo **Direct** do plugin ou executar o **Standalone Relay** próprio e publicar o serviço com HTTPS/WSS. Isso é uma etapa de infraestrutura separada do GitHub Pages.

## Publicar no GitHub Pages

1. Crie um repositório no GitHub, por exemplo `berry-voice`.
2. Envie todos os arquivos desta pasta para a raiz do repositório.
3. Abra **Settings → Pages**.
4. Em **Build and deployment**, selecione **Deploy from a branch**, escolha `main` e `/ (root)`.
5. Em **Custom domain**, informe `voice.berrymc.net.br` e salve.
6. No provedor DNS do domínio `berrymc.net.br`, crie:

   - Tipo: `CNAME`
   - Nome/Host: `voice`
   - Destino/Target: `SEU-USUARIO.github.io` (ou o domínio GitHub Pages da sua organização)

7. Aguarde o DNS propagar. Quando o GitHub liberar, ative **Enforce HTTPS** em Settings → Pages.

O arquivo `CNAME` já está incluído. Não coloque o `network.secret` do plugin em nenhum arquivo deste repositório: ele é segredo de servidor e nunca deve ser publicado no site.

## Testar localmente

Abra `index.html` diretamente no navegador para conferir o layout. Para testar a cópia automática, use um servidor local ou publique em HTTPS; alguns navegadores bloqueiam Clipboard API fora de contextos seguros.

## Personalização

- Cores, layout e responsividade: `styles.css`
- Fluxo de código/cópia/abertura do cliente: `app.js`
- Conteúdo e marcação: `index.html`
