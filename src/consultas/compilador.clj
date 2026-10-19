(ns consultas.compilador)

;; Validacao dos campos e tipos

(defn erro [mensagem]
  (throw (ex-info mensagem {:tipo :consulta-invalida})))

(defn tipo-literal [valor]
  (cond
    (string? valor) :texto
    (integer? valor) :inteiro
    (number? valor) :decimal
    :else (erro "Tipo de literal invalido")))

(defn numerico? [tipo]
  (contains? #{:inteiro :decimal} tipo))

(defn tipo-campo [esquema campo]
  (let [tipos (into {} esquema)]
    (if (contains? tipos campo)
      (get tipos campo)
      (erro (str "Campo inexistente: " (name campo))))))

(defn validar-numerico [esquema campo]
  (when-not (numerico? (tipo-campo esquema campo))
    (erro (str "Campo exige valor numerico: " (name campo)))))

(defn exigir-argumentos [op args quantidade]
  (when (not= (count args) quantidade)
    (erro (str "Quantidade invalida de argumentos em " op))))

(defn validar-expressao [esquema [op & args]]
  (case op
    :e
    (do
      (exigir-argumentos op args 2)
      (run! (partial validar-expressao esquema) args))

    :ou
    (do
      (exigir-argumentos op args 2)
      (run! (partial validar-expressao esquema) args))

    :nao
    (do
      (exigir-argumentos op args 1)
      (validar-expressao esquema (first args)))

    (:= :!= :< :<= :> :>=)
    (do
      (exigir-argumentos op args 2)
      (let [[campo valor] args
            tipo (tipo-campo esquema campo)
            tipo-valor (tipo-literal valor)]
        (when-not
          (or (= tipo tipo-valor)
              (and (numerico? tipo)
                   (numerico? tipo-valor)))
          (erro (str "Comparacao incompativel no campo "
                     (name campo))))))))

(defn validar-agregacao [esquema [operacao & args]]
  (case operacao
    :contar
    (exigir-argumentos operacao args 0)

    :soma
    (do
      (exigir-argumentos operacao args 1)
      (validar-numerico esquema (first args)))

    :media
    (do
      (exigir-argumentos operacao args 1)
      (validar-numerico esquema (first args)))))

(defn validar-estagio [esquema [tipo & args]]
  (case tipo
    :onde
    (do
      (exigir-argumentos tipo args 1)
      (validar-expressao esquema (first args)))

    :limitar
    (do
      (exigir-argumentos tipo args 1)
      (when-not (and (integer? (first args))
                     (<= 0 (first args)))
        (erro "Limite invalido")))

    :ordenar-por
    (do
      (exigir-argumentos tipo args 2)
      (tipo-campo esquema (first args))
      (when-not (#{:asc :desc} (second args))
        (erro "Ordem invalida")))

    :selecionar
    (do
      (exigir-argumentos tipo args 1)
      (when-not (and (vector? (first args))
                     (seq (first args)))
        (erro "Selecao deve conter um vetor de campos"))
      (run! (partial tipo-campo esquema)
            (first args)))))

(defn validar-terminal [esquema [tipo & args]]
  (case tipo
    :contar
    (exigir-argumentos tipo args 0)

    :soma
    (do
      (exigir-argumentos tipo args 1)
      (validar-numerico esquema (first args)))

    :media
    (do
      (exigir-argumentos tipo args 1)
      (validar-numerico esquema (first args)))

    :agrupar-por
    (do
      (exigir-argumentos tipo args 2)
      (tipo-campo esquema (first args))
      (validar-agregacao esquema (second args)))))

(defn validar-consulta [consulta esquema]
  (let [estagios (:estagios consulta)
        terminal (:terminal consulta)
        selecoes (keep-indexed
                   (fn [i estagio]
                     (when (= :selecionar (first estagio)) i))
                   estagios)]
    (when (or (> (count selecoes) 1)
              (and (seq selecoes)
                   (or terminal
                       (not= (first selecoes)
                             (dec (count estagios))))))
      (erro "Selecionar deve ser o ultimo elemento"))

    (run! (partial validar-estagio esquema) estagios)

    (when terminal
      (validar-terminal esquema terminal))

    consulta))

;; Compilacao das expressoes em predicados

(defn compilar-expressao [[op & args]]
  (case op
    :e
    (let [[a b] args
          f (compilar-expressao a)
          g (compilar-expressao b)]
      (fn [registro]
        (and (f registro) (g registro))))

    :ou
    (let [[a b] args
          f (compilar-expressao a)
          g (compilar-expressao b)]
      (fn [registro]
        (or (f registro) (g registro))))

    :nao
    (let [f (compilar-expressao (first args))]
      (fn [registro]
        (not (f registro))))

    (:= :!= :< :<= :> :>=)
    (let [[campo valor] args
          comparar
          (case op
            := (fn [a b]
                 (if (and (number? a) (number? b))
                   (== a b)
                   (= a b)))

            :!= (fn [a b]
                  (if (and (number? a) (number? b))
                    (not (== a b))
                    (not= a b)))

            :< (fn [a b]
                 (if (and (string? a) (string? b))
                   (neg? (compare a b))
                   (< a b)))

            :<= (fn [a b]
                  (if (and (string? a) (string? b))
                    (not (pos? (compare a b)))
                    (<= a b)))

            :> (fn [a b]
                 (if (and (string? a) (string? b))
                   (pos? (compare a b))
                   (> a b)))

            :>= (fn [a b]
                  (if (and (string? a) (string? b))
                    (not (neg? (compare a b)))
                    (>= a b))))]
      (fn [registro]
        (comparar (get registro campo) valor)))))

;; Compilacao dos estagios

(defn compilar-estagio [[tipo & args]]
  (case tipo
    :onde
    (partial filter (compilar-expressao (first args)))

    :limitar
    (partial take (first args))

    :ordenar-por
    (let [[campo ordem] args
          comparador (if (= ordem :desc)
                       #(compare %2 %1)
                       compare)]
      (partial sort-by campo comparador))

    :selecionar
    (let [campos (first args)]
      (partial map
               (fn [registro]
                 (select-keys registro campos))))))

(defn compilar [consulta]
  (reduce
    (fn [anterior estagio]
      (comp (compilar-estagio estagio) anterior))
    identity
    (:estagios consulta)))

;; Agregacoes funcionais com reduce

(defn agregar [operacao campo registros]
  (case operacao
    :contar
    (reduce (fn [total _]
              (inc total))
            0
            registros)

    :soma
    (reduce (fn [total registro]
              (+ total (get registro campo)))
            0
            registros)

    :media
    (let [[soma quantidade]
          (reduce
            (fn [[total n] registro]
              [(+ total (get registro campo))
               (inc n)])
            [0 0]
            registros)]
      (if (zero? quantidade)
        0.0
        (/ (double soma) quantidade)))))

(defn executar-terminal [terminal registros]
  (let [[tipo & argumentos] terminal]
    (case tipo
      :contar
      (agregar :contar nil registros)

      :soma
      (agregar :soma (first argumentos) registros)

      :media
      (agregar :media (first argumentos) registros)

      :agrupar-por
      (let [[campo [operacao campo-agregacao]] argumentos
            grupos (group-by campo registros)]
        (mapv
          (fn [[chave grupo]]
            [chave (agregar operacao campo-agregacao grupo)])
          (sort-by first grupos))))))