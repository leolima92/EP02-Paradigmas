(ns consultas.sintaxe
  (:require [clojure.string :as str]))

(def padrao-token
  #"(?s)^(\s+|\"[^\"]*\"|>=|<=|!=|[=<>|(),]|-?\d+(?:\.\d+)?|[A-Za-z_][A-Za-z0-9_]*)")

(defn tokenizar [texto]
  (loop [restante texto
         tokens []]
    (if (empty? restante)
      tokens
      (if-let [token (re-find padrao-token restante)]
        (let [valor (second token)]
          (recur (subs restante (count valor))
                 (if (str/blank? valor)
                   tokens
                   (conj tokens valor))))
        (throw (ex-info
                (str "Token invalido: " restante)
                {:texto restante}))))))

(defn erro [mensagem]
  (throw (ex-info mensagem {:tipo :sintaxe})))

(defn literal [token]
  (cond
    (nil? token)
    (erro "Literal esperado")

    (re-matches #"\"[^\"]*\"" token)
    (subs token 1 (dec (count token)))

    (re-matches #"-?\d+" token)
    (Long/parseLong token)

    (re-matches #"-?\d+\.\d+" token)
    (Double/parseDouble token)

    :else
    (erro (str "Literal invalido: " token))))

(def operadores
  {"=" :=
   "!=" :!=
   "<" :<
   "<=" :<=
   ">" :>
   ">=" :>=})

(declare analisar-ou)

(defn analisar-comparacao [tokens]
  (let [[campo operador valor & resto] tokens]
    (when-not (re-matches #"[A-Za-z_][A-Za-z0-9_]*"
                          (or campo ""))
      (erro "Campo esperado"))

    (when-not (contains? operadores operador)
      (erro (str "Operador invalido: " operador)))

    [ [(get operadores operador)
       (keyword campo)
       (literal valor)]
      resto]))

(defn analisar-primaria [tokens]
  (if (= "(" (first tokens))
    (let [[expr resto] (analisar-ou (rest tokens))]
      (if (= ")" (first resto))
        [expr (rest resto)]
        (erro "Parentese sem fechar")))
    (analisar-comparacao tokens)))

(defn analisar-nao [tokens]
  (if (= "nao" (first tokens))
    (let [[expr resto] (analisar-nao (rest tokens))]
      [[:nao expr] resto])
    (analisar-primaria tokens)))

(defn analisar-e [tokens]
  (let [[primeiro restante] (analisar-nao tokens)]
    (loop [resultado primeiro
           tokens restante]
      (if (= "e" (first tokens))
        (let [[direita resto] (analisar-nao (rest tokens))]
          (recur [:e resultado direita] resto))
        [resultado tokens]))))

(defn analisar-ou [tokens]
  (let [[primeiro restante] (analisar-e tokens)]
    (loop [resultado primeiro
           tokens restante]
      (if (= "ou" (first tokens))
        (let [[direita resto] (analisar-e (rest tokens))]
          (recur [:ou resultado direita] resto))
        [resultado tokens]))))

(defn analisar-expressao [texto]
  (let [[expr restante] (analisar-ou (tokenizar texto))]
    (if (empty? restante)
      expr
      (erro (str "Tokens inesperados: " restante)))))

;; Analise dos elementos de uma consulta completa

(defn exigir [condicao mensagem]
  (when-not condicao
    (erro mensagem)))

(defn campo-valido [token]
  (boolean
    (re-matches #"[A-Za-z_][A-Za-z0-9_]*"
                (or token ""))))

(defn ler-campo [token]
  (exigir (campo-valido token) "Campo invalido")
  (keyword token))

(defn ler-natural [token]
  (exigir (and token (re-matches #"\d+" token))
          "Numero inteiro nao negativo esperado")
  (Long/parseLong token))

(defn separar-estagios [tokens]
  (loop [restante tokens
         atual []
         resultado []]
    (if (empty? restante)
      (if (empty? atual)
        (if (empty? resultado)
          []
          (erro "Estagio vazio"))
        (conj resultado atual))
      (if (= "|" (first restante))
        (do
          (exigir (seq atual) "Estagio vazio")
          (recur (rest restante) []
                 (conj resultado atual)))
        (recur (rest restante)
               (conj atual (first restante))
               resultado)))))

(defn analisar-agregacao [tokens]
  (case (first tokens)
    "contar"
    (do
      (exigir (= 1 (count tokens))
              "Argumentos invalidos em contar")
      [:contar])

    "soma"
    (do
      (exigir (= 2 (count tokens))
              "Soma exige um campo")
      [:soma (ler-campo (second tokens))])

    "media"
    (do
      (exigir (= 2 (count tokens))
              "Media exige um campo")
      [:media (ler-campo (second tokens))])

    (erro "Agregacao desconhecida")))

(defn analisar-elemento [tokens]
  (let [[comando & argumentos] tokens]
    (case comando
      "onde"
      (do
        (exigir (seq argumentos) "Expressao esperada")
        [:estagio
         [:onde (analisar-expressao
                  (str/join " " argumentos))]])

      "limitar"
      (do
        (exigir (= 1 (count argumentos))
                "Limitar exige um numero")
        [:estagio [:limitar (ler-natural (first argumentos))]])

      "ordenar"
      (let [[por campo ordem & extras] argumentos]
        (exigir (= por "por") "Esperado: ordenar por")
        (exigir (empty? extras) "Ordenacao invalida")
        (exigir (or (nil? ordem) (= ordem "desc"))
                "Ordem invalida")
        [:estagio
         [:ordenar-por (ler-campo campo)
          (if (= ordem "desc") :desc :asc)]])

      "selecionar"
      (do
        (exigir (and (seq argumentos)
                     (odd? (count argumentos)))
                "Lista de campos invalida")
        (exigir (every? #(= "," %)
                        (take-nth 2 (rest argumentos)))
                "Separador de campos invalido")
        [:final
         [:selecionar
          (mapv ler-campo (take-nth 2 argumentos))]])

      "contar"
      [:final (analisar-agregacao tokens)]

      "soma"
      [:final (analisar-agregacao tokens)]

      "media"
      [:final (analisar-agregacao tokens)]

      "agrupar"
      (let [[por campo com & agregacao] argumentos]
        (exigir (= por "por") "Esperado: agrupar por")
        (exigir (= com "com") "Esperado: com")
        [:final
         [:agrupar-por
          (ler-campo campo)
          (analisar-agregacao agregacao)]])

      (erro (str "Estagio desconhecido: " comando)))))

(defn analisar [texto]
  (let [partes (separar-estagios (tokenizar texto))
        elementos (mapv analisar-elemento partes)
        finais (keep-indexed
                 (fn [i [tipo _]]
                   (when (= tipo :final) i))
                 elementos)]
    (exigir (or (empty? finais)
                (and (= 1 (count finais))
                     (= (first finais)
                        (dec (count elementos)))))
            "Elemento final deve ser o ultimo")

    (reduce
      (fn [consulta [tipo elemento]]
        (if (= tipo :estagio)
          (update consulta :estagios conj elemento)
          (if (= :selecionar (first elemento))
            (update consulta :estagios conj elemento)
            (assoc consulta :terminal elemento))))
      {:estagios [] :terminal nil}
      elementos)))