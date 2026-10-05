# noxguard

**Guardrails determinísticos para chats e agentes com LLM, em Java.** Uma guarda de saída para streaming que nunca deixa sair um vazamento e nunca segura a resposta inteira, lista de links permitidos, delimitação de dados, histórico assinado, portão de proposta para agentes e versões decodificadas da entrada para o seu classificador. Nenhuma dependência no núcleo.

[![Maven Central](https://img.shields.io/maven-central/v/com.marcusrdrigues/noxguard-core)](https://central.sonatype.com/artifact/com.marcusrdrigues/noxguard-core)

[Read in English](README.md)


## Por quê

Uma resposta de LLM em streaming sai do servidor em pedaços. Se o modelo começa a recitar as instruções, as primeiras palavras chegam à tela antes de qualquer checagem na resposta inteira. As soluções comuns trocam um problema por outro: checar no fim exige segurar o stream (o usuário espera a resposta toda; é assim que a guarda de saída do LangChain4j funciona num stream), e checar cada pedaço não vê um marcador dividido em dois ("Você é" + " Ava").

O noxguard segura só os últimos `maior marcador − 1` caracteres e libera todo o resto na hora. O marcador sempre aparece inteiro antes de qualquer letra dele sair.

Agentes trazem um segundo problema: um modelo com ferramentas pode propor uma ação que não devia, e uma regra no prompt só diminui quantas vezes isso acontece. No [Nox](https://marcusrdrigues.com), com a regra no prompt, o modelo ainda propôs um e-mail ao RH em nome do dono uma vez em seis. O que segurou todas as vezes foi código: a proposta fica retida até a resposta terminar e é descartada se a resposta for uma recusa. Isso é o `ProposalGate`.

Essas guardas vêm do Nox, o chat público do portfólio do autor, medidas com o [noxeval](https://github.com/marcusrdrigues/noxeval). **O noxeval mede, o noxguard aplica.**

## O que tem dentro

| Guarda | Pacote | Impede |
| --- | --- | --- |
| `StreamGuard` | `output` | Vazamento do prompt ou resposta sem fim, durante o streaming |
| `LinkPolicy` | `output` | Exfiltração por link ou imagem Markdown para fora |
| `DataEnvelope` | `data` | Trechos recuperados ou resultados de ferramenta virando instrução |
| `HistorySigner` | `history` | Histórico forjado devolvido pelo cliente |
| `ProposalGate`, `ToolBudget`, `StrictSchema` | `agent` | Agente agindo além do pedido, ou em loop sem fim |
| `InputViews` | `input` | Ataque escondido em base64, ROT13, leetspeak ou caracteres invisíveis, para o seu classificador ver |

O `noxguard-reactor` transforma um `Flux<String>` do Spring AI, do WebFlux ou de qualquer fonte Reactor em eventos guardados.

## Instalação e uso

Java 21 ou superior, no Maven Central como `com.marcusrdrigues:noxguard-core:0.1.0` (e `noxguard-reactor` para `Flux`). As coordenadas Maven, os exemplos de código e os limites estão no [README em inglês](README.md#install), que é a referência. O app de exemplo com Spring Boot está em [`examples/chat-spring-boot`](examples/chat-spring-boot).

## Limites, sem rodeio

- A guarda de streaming pega marcador literal; vazamento parafraseado passa (a defesa aí é não ter segredo no prompt).
- O link é checado no fim do stream: mostre a resposta como texto puro até ela terminar.
- `InputViews` prepara a entrada para um classificador; não é um classificador.
- Uma guarda não prova nada sobre o modelo: o `ProposalGate` torna o erro do modelo inofensivo, e quantas vezes o modelo erra se mede com o noxeval.

## Licença

[MIT](LICENSE)
