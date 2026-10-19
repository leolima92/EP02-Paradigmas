(ns consultas.core
  (:require [clojure.string :as str]
            [consultas.sintaxe :as sintaxe]
            [consultas.compilador :as compilador]))

(defn converter-valor [tipo valor]
  (case tipo
    :inteiro (Long/parseLong valor)
    :decimal (Double/parseDouble valor)
    :texto valor))

(defn analisar-campo [campo]
  (let [[nome tipo] (str/split campo #":" 2)]
    [(keyword nome) (keyword tipo)]))

(defn carregar-csv [conteudo]
  (let [linhas (str/split-lines conteudo)
        esquema (mapv analisar-campo
                     (str/split (first linhas) #";"))
        registros
        (mapv
          (fn [linha]
            (let [valores (str/split linha #";" -1)]
              (into {}
                    (map (fn [[campo tipo] valor]
                           [campo (converter-valor tipo valor)])
                         esquema
                         valores))))
          (rest linhas))]
    {:esquema esquema
     :registros registros}))

(defn consulta []
  {:estagios []
   :terminal nil})

(defn acrescentar-estagio [c estagio]
  (if (or (:terminal c)
          (some #(= :selecionar (first %)) (:estagios c)))
    (throw (ex-info
             "Nao e permitido adicionar estagios apos um elemento final"
             {:consulta c}))
    (update c :estagios conj estagio)))

(defn onde [c expr]
  (acrescentar-estagio c [:onde expr]))

(defn limitar [c n]
  (acrescentar-estagio c [:limitar n]))

(defn ordenar-por [c campo ordem]
  (acrescentar-estagio c [:ordenar-por campo ordem]))

(defn selecionar [c campos]
  (acrescentar-estagio c [:selecionar campos]))

(defn analisar [texto]
  (sintaxe/analisar texto))

(defn executar [c dados]
  (try
    (let [esquema (:esquema dados)
          registros (:registros dados)

          ;; Validar antes de executar
          _ (compilador/validar-consulta c esquema)

          ;; Compilar a AST
          pipeline (compilador/compilar c)

          ;; Executar as funcoes compostas
          resultado (pipeline registros)]

      (if-let [terminal (:terminal c)]
        (compilador/executar-terminal terminal resultado)
        (vec resultado)))

    (catch clojure.lang.ExceptionInfo e
      {:erros [(ex-message e)]})

    (catch IllegalArgumentException e
      {:erros [(.getMessage e)]})))