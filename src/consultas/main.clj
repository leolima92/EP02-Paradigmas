(ns consultas.main
  (:require [clojure.string :as str]
            [consultas.core :as core])
  (:gen-class))

;; Formata os valores conforme o tipo definido no CSV.
(defn formatar-valor [valor tipo]
  (if (= tipo :decimal)
    (String/format
      java.util.Locale/ROOT
      "%.2f"
      (to-array [(double valor)]))
    (str valor)))

(defn tipo-do-campo [esquema campo]
  (get (into {} esquema) campo))

(defn campos-da-consulta [consulta esquema]
  (if-let [selecao
           (some #(when (= :selecionar (first %)) %)
                 (:estagios consulta))]
    (second selecao)
    (mapv first esquema)))

(defn formatar-registro [registro campos esquema]
  (str/join
    "; "
    (map (fn [campo]
           (formatar-valor
             (get registro campo)
             (tipo-do-campo esquema campo)))
         campos)))

(defn tipo-agregacao [agregacao esquema]
  (let [[operacao campo] agregacao]
    (case operacao
      :contar :inteiro
      :soma (tipo-do-campo esquema campo)
      :media :decimal)))

(defn formatar-resultado [consulta dados resultado]
  (let [esquema (:esquema dados)
        terminal (:terminal consulta)]

    (cond
      ;; Erros de validacao
      (and (map? resultado) (:erros resultado))
      (str "ERRO: " (str/join "; " (:erros resultado)))

      ;; Resultados com agregacao ou agrupamento
      terminal
      (let [[operacao & args] terminal]
        (case operacao
          :contar
          (str resultado)

          :soma
          (formatar-valor
            resultado
            (tipo-agregacao terminal esquema))

          :media
          (formatar-valor resultado :decimal)

          :agrupar-por
          (let [[campo agregacao] args
                tipo-chave (tipo-do-campo esquema campo)
                tipo-resultado (tipo-agregacao agregacao esquema)]
            (if (empty? resultado)
              "(vazio)"
              (str/join
                "\n"
                (map
                  (fn [[chave valor]]
                    (str
                      (formatar-valor chave tipo-chave)
                      "; "
                      (formatar-valor valor tipo-resultado)))
                  resultado))))))

      ;; Consulta que devolve registros
      (empty? resultado)
      "(vazio)"

      :else
      (str/join
        "\n"
        (map
          (fn [registro]
            (formatar-registro
              registro
              (campos-da-consulta consulta esquema)
              esquema))
          resultado)))))

(defn processar-comando [linha dados]
  (cond
    (= linha "EXIT")
    :sair

    (str/blank? linha)
    "ERRO: comando vazio"

    (or (= linha "QUERY")
        (str/starts-with? linha "QUERY "))
    (let [texto (if (= linha "QUERY")
                  ""
                  (subs linha 6))
          consulta (core/analisar texto)
          resultado (core/executar consulta dados)]
      (formatar-resultado consulta dados resultado))

    :else
    "ERRO: comando desconhecido"))

(defn terminal [dados]
  (loop []
    (print "> ")
    (flush)
    (when-let [linha (read-line)]
      (let [resultado
            (try
              (processar-comando linha dados)
              (catch Exception e
                (str "ERRO: " (.getMessage e))))]

        (when-not (= resultado :sair)
          (println resultado)
          (recur))))))

(defn -main [& args]
  (try
    (if-let [caminho (first args)]
      (let [conteudo (slurp caminho)
            dados (core/carregar-csv conteudo)]
        (terminal dados))
      (println "ERRO: informe o caminho do arquivo CSV"))

    (catch Exception e
      (println (str "ERRO: " (.getMessage e))))))