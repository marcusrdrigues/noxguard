# noxguard

**Guardrails determinísticos para chats e agentes com LLM, em Java.** Uma guarda de saída para streaming que nunca deixa sair um vazamento e nunca segura a resposta inteira, lista de links permitidos, delimitação de dados, histórico assinado, política de ferramentas para agentes (tudo negado por padrão), portão de proposta, conferência de citações em respostas com RAG e, para o seu classificador de entrada, versões decodificadas da mensagem, tempo limite e uma decisão explícita para quando ele cai. Nenhuma dependência no núcleo, e um starter de Spring Boot que monta tudo a partir de propriedades.

[![Maven Central](https://img.shields.io/maven-central/v/com.marcusrdrigues/noxguard-core)](https://central.sonatype.com/artifact/com.marcusrdrigues/noxguard-core)

[Read in English](README.md)


## Por quê

Uma resposta de LLM em streaming sai do servidor em pedaços. Se o modelo começa a recitar as instruções, as primeiras palavras chegam à tela antes de qualquer checagem na resposta inteira. As soluções comuns trocam um problema por outro: checar no fim exige segurar o stream (o usuário espera a resposta toda; é assim que a guarda de saída do LangChain4j funciona num stream), e checar cada pedaço não vê um marcador dividido em dois ("Você é" + " Ava").

O noxguard segura só os últimos `maior marcador − 1` caracteres e libera todo o resto na hora. O marcador sempre aparece inteiro antes de qualquer letra dele sair.

Agentes trazem um segundo problema: um modelo com ferramentas pode propor uma ação que não devia, e uma regra no prompt só diminui quantas vezes isso acontece. No [Nox](https://marcusrdrigues.com), com a regra no prompt, o modelo ainda propôs um e-mail ao RH em nome do dono uma vez em seis. O que segurou todas as vezes foi código: a proposta fica retida até a resposta terminar e é descartada se a resposta for uma recusa. Isso é o `ProposalGate`.

Antes de uma ferramenta rodar, alguém precisa decidir se ela pode: a ferramenta é permitida, os argumentos são aceitáveis, a resposta ainda tem chamadas, uma pessoa precisa confirmar? O `ToolPolicy` é essa decisão num objeto só, conferida antes de cada chamada. Ele está para as ferramentas de um agente como o Spring Security está para os endpoints de uma aplicação web: tudo negado por padrão, uma regra por ferramenta e uma decisão que a aplicação executa (rodar, negar com uma mensagem que o modelo lê, ou pedir confirmação ao usuário). O modelo pode pedir qualquer coisa; a política decide o que roda (OWASP LLM06, agência excessiva).

Um prompt de RAG diz "use só os trechos, nunca invente número ou nome". Isso também é probabilidade. O `CitationGuard` faz isso em código: os trechos vão numerados, o modelo termina cada frase com o número da fonte, e cada número, sigla e nome da frase é conferido contra a fonte citada antes de a resposta ficar pronta. Se o detalhe está em outro trecho que o modelo recebeu, a citação é corrigida; se não está em trecho nenhum, a frase sai. Num produto jurídico ou financeiro esse é o risco central: um número de processo, um tribunal ou uma data que nenhuma fonte tem.

Essas guardas vêm do Nox, o chat público do portfólio do autor, medidas com o [noxeval](https://github.com/marcusrdrigues/noxeval). **O noxeval mede, o noxguard aplica.**

## O que tem dentro

| Guarda | Pacote | Impede |
| --- | --- | --- |
| `StreamGuard` | `output` | Vazamento do prompt ou resposta sem fim, durante o streaming |
| `LinkPolicy` | `output` | Exfiltração por link ou imagem Markdown para fora |
| `DataEnvelope` | `data` | Trechos recuperados ou resultados de ferramenta virando instrução |
| `HistorySigner` | `history` | Histórico forjado devolvido pelo cliente |
| `ToolPolicy` | `agent` | Chamada de ferramenta que o agente não recebeu, argumento ruim, chamadas demais, ou efeito colateral sem confirmação do usuário |
| `ProposalGate`, `ToolBudget`, `StrictSchema` | `agent` | Agente agindo além do pedido, ou em loop sem fim |
| `CitationGuard` | `grounding` | Número, sigla ou nome numa resposta com RAG que nenhuma fonte tem, ou citação apontando para a fonte errada |
| `InputViews` | `input` | Ataque escondido em base64, ROT13, leetspeak ou caracteres invisíveis, para o seu classificador ver |
| `GuardedClassifier` | `input` | Classificador de entrada que trava ou falha, e uma aplicação que nunca decidiu o que acontece nessa hora |

O `noxguard-reactor` transforma um `Flux<String>` do Spring AI, do WebFlux ou de qualquer fonte Reactor em eventos guardados, com a conferência de citações no fim quando você pede. O `noxguard-spring-boot-starter` cria as guardas, a política de ferramentas, o `CitationGuard` e o `GuardedClassifier` a partir das propriedades `noxguard.*`, e impede a aplicação de subir com configuração insegura (segredo curto, regex inválida, classificador sem `noxguard.input.on-failure`).

## Instalação e uso

Java 21 ou superior, no Maven Central como `com.marcusrdrigues:noxguard-core:0.3.0` (e `noxguard-reactor` para `Flux`, `noxguard-spring-boot-starter` para Spring Boot 4). As coordenadas Maven, os exemplos de código e os limites estão no [README em inglês](README.md#install), que é a referência. O app de exemplo com Spring Boot está em [`examples/chat-spring-boot`](examples/chat-spring-boot).

## Limites, sem rodeio

- A guarda de streaming pega marcador literal; vazamento parafraseado passa (a defesa aí é não ter segredo no prompt).
- O link é checado no fim do stream: mostre a resposta como texto puro até ela terminar.
- `InputViews` prepara a entrada para um classificador; não é um classificador. O `GuardedClassifier` decide o que acontece quando o seu falha, não o quanto ele acerta.
- O `CitationGuard` confere detalhes, não sentido: "trabalhou em" virar "liderou" passa, porque as duas são palavras comuns. Conferir sentido exige um modelo; meça com o juiz do noxeval.
- Número escrito por extenso na resposta ("duas cópias") não é conferido. Número em algarismos é; por extenso nas fontes ("dez mil") conta como número.
- Citação só dá para conferir quando a frase termina; num stream, o usuário pode ver uma frase que depois sai. Mostre como texto puro até o fim, como com os links.
- As regras de número por extenso e de frase que depende da anterior ("Ele", "Isso") cobrem português e inglês.
- Uma guarda não prova nada sobre o modelo: o `ProposalGate` torna o erro do modelo inofensivo, e quantas vezes o modelo erra se mede com o noxeval.
- O `ToolPolicy` decide sobre a chamada que o modelo pediu; ele não torna a ferramenta segura. Uma ferramenta que apaga dados precisa de autorização própria no sistema que ela toca.
- Regra de argumento confere formato, não intenção: um slug no padrão não quer dizer que esse usuário pode ver aquele caso. Autorizar o dado é papel da aplicação.

## Licença

[MIT](LICENSE)
