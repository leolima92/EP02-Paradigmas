# Integrantes

- Leonardo Rodrigues de Lima
- Eduardo Leal Aruth
- Yasmin Ayumi Foltran Mano

# EP02 — Query Builder Funcional em Clojure

Motor de consultas para dados CSV, desenvolvido com os princípios do paradigma funcional. As consultas são analisadas para uma árvore sintática abstrata (AST), validadas conforme o esquema do arquivo e compiladas em funções compostas para processar os registros.

## Requisitos e execução

- Java instalado.
- Clojure CLI com suporte a `deps.edn` e `-M`.
- Nenhuma biblioteca externa além do próprio Clojure.

Na raiz do repositório:

```bash
clojure -M:run dados/filmes.csv
```

Na interface interativa, use `QUERY` seguido da consulta, `QUERY` sozinho para listar os registros e `EXIT` para encerrar. O fim da entrada também encerra o terminal.

Exemplo:

```text
> QUERY onde genero = "drama" e nota >= 8 | ordenar por nota desc | selecionar titulo, nota
Cidade de Deus; 8.60
Ainda Estou Aqui; 8.20
Central do Brasil; 8.00
> QUERY agrupar por genero com contar
acao; 1
comedia; 1
drama; 5
suspense; 1
> EXIT
```

## Formato dos dados

A primeira linha do CSV declara os campos e seus tipos, usando `;` como separador, com tipos `texto`, `inteiro` e `decimal`:

```text
titulo:texto;ano:inteiro;genero:texto;nota:decimal;duracao:inteiro
Cidade de Deus;2002;drama;8.6;130
```

O código não depende desses nomes: o esquema é extraído do cabeçalho e cada linha vira um mapa com chaves do tipo palavra-chave (`keyword`).

## Estrutura da AST

A AST é uma estrutura imutável composta de **dados puros**, sem funções. Seu formato raiz é:

```clojure
{:estagios [<estagio> ...]
 :terminal <terminal-ou-nil>}
```

Exemplo de consulta com seleção:

```clojure
{:estagios [[:onde [:e [:= :genero "drama"] [:>= :nota 8]]]
            [:ordenar-por :nota :desc]
            [:limitar 2]
            [:selecionar [:titulo :nota]]]
 :terminal nil}
```

Exemplo de consulta com agregação terminal:

```clojure
{:estagios [[:onde [:>= :ano 2010]]]
 :terminal [:agrupar-por :genero [:contar]]}
```

### Tipos de nós

- Filtro: `[:onde <expr>]`
- Ordenação: `[:ordenar-por :campo :asc]` ou `[:ordenar-por :campo :desc]`
- Limite: `[:limitar n]`
- Seleção (último estágio): `[:selecionar [:campo1 :campo2]]`
- Terminal de contagem: `[:contar]`
- Terminal de soma: `[:soma :campo]`
- Terminal de média: `[:media :campo]`
- Agrupamento: `[:agrupar-por :campo [:contar]]`, `[:agrupar-por :campo [:soma :outro-campo]]` ou `[:agrupar-por :campo [:media :outro-campo]]`
- Expressão de comparação: `[:= :campo valor]`, `[:!= :campo valor]`, `[:< :campo valor]`, `[:<= :campo valor]`, `[:> :campo valor]`, `[:>= :campo valor]`
- Expressão lógica: `[:e expr1 expr2]`, `[:ou expr1 expr2]`, `[:nao expr]`

A seleção fica no vetor `:estagios`, enquanto contagem, soma, média e agrupamento ficam em `:terminal`. Não pode haver estágios após uma seleção ou terminal. A ausência de ambos é representada por `{:estagios [] :terminal nil}`.

O analisador de texto retorna a mesma AST que pode ser construída pelo builder. A igualdade pode ser verificada com `=`.

## Namespaces

| Namespace | Responsabilidade |
|---|---|
| `consultas.main` | Carrega o arquivo, executa o laço interativo com `loop`/`recur`, formata e imprime resultados e erros. |
| `consultas.sintaxe` | Tokeniza o texto e faz análise sintática recursiva; produz a AST. |
| `consultas.compilador` | Valida a AST contra o esquema, compila expressões e estágios com `case`, compõe as funções com `reduce`/`comp` e realiza agregações com `reduce`. |
| `consultas.core` | Converte o conteúdo CSV em registros, expõe o query builder, `analisar` e `executar`. |

## Interface pública do builder

As funções estão disponíveis em `consultas.core`:

```clojure
(require '[consultas.core :as consultas])

(def base (consultas/consulta))
(def filtrada (consultas/onde base [:= :genero "drama"]))
(def duas (consultas/limitar filtrada 2))
(def tres (consultas/limitar filtrada 3))

(= (consultas/analisar "onde genero = \"drama\" | limitar 2") duas)
;; => true
```

A execução usa `(consultas/executar consulta dados)`, onde `dados` tem a forma `{:esquema [[campo tipo] ...] :registros seq}`. O resultado é uma sequência de mapas, um valor agregado, grupos agregados ou `{:erros [...]}` quando a consulta for inválida.

## Testes e verificação

Usamos o arquivo `casos_teste_ep02.txt` como referência para conferir as consultas e as saídas esperadas. Além dos testes feitos manualmente no terminal, criamos scripts locais para verificar automaticamente os resultados. Esses scripts foram mantidos fora da entrega, pois serviram de apoio ao desenvolvimento.

Na revisão, foram aprovados 46 cenários de consultas e erros, 7 testes do builder, 5 testes complementares e 7 testes de estrutura da AST. Também testamos o programa com um CSV de produtos, de campos diferentes, e fizemos testes separados para nós desconhecidos e expressões malformadas. Quando algum resultado divergiu do esperado, corrigimos a implementação e repetimos os testes.

Os testes realizados ajudam a verificar o funcionamento, mas não garantem antecipadamente o resultado de testes adicionais da correção.

## Uso de inteligência artificial

Utilizamos IA generativa ao longo do desenvolvimento como ferramenta de apoio. Ela ajudou a entender recursos do Clojure, organizar a implementação, sugerir trechos de código e investigar erros encontrados nas execuções. Uma parte importante desse uso foi a **criação e ampliação de testes automatizados locais**: usamos a ferramenta para propor casos de teste, comparar saídas com o enunciado e levantar situações que não tínhamos testado inicialmente, como resultados vazios, tipos numéricos diferentes, consultas inválidas e ASTs montadas manualmente.

As respostas da IA não foram consideradas corretas automaticamente. Executamos o código no nosso ambiente, analisamos as saídas e fizemos ajustes quando os testes apontaram problemas. Por exemplo, corrigimos a comparação entre inteiros e decimais e reforçamos a validação de argumentos da AST depois de identificar esses pontos nos testes.

### Exemplos de prompts utilizados

As frases abaixo resumem pedidos feitos à ferramenta durante o trabalho. São **paráfrases das interações**, não cópias literais de todo o histórico.

1. "Como verificar se Java, Git e Clojure CLI estão instalados e preparar o projeto para rodar no VS Code?"
2. "Como representar uma consulta com mapas e vetores imutáveis e criar novas consultas sem modificar a original?"
3. "Como analisar expressões com `nao`, `e`, `ou` e parênteses respeitando a precedência?"
4. "Como transformar os estágios da AST em funções usando `case`, `partial`, `comp` e `reduce`?"
5. "Por que a consulta `nota = 8` não encontra registros cuja nota é `8.0`? Como corrigir e testar isso?"
6. "Como montar testes automatizados para comparar as saídas do programa com os resultados do arquivo de casos de teste?"
7. "Como testar consultas inválidas, um nó desconhecido da AST e um CSV com campos diferentes sem derrubar o programa?"
8. "Como verificar se as correções no compilador não quebraram os testes que já tinham passado?"
