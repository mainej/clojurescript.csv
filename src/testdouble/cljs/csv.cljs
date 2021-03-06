(ns testdouble.cljs.csv
  (:require [clojure.string :as str]))

(defn- escape-quotes [s]
  (str/replace s "\"" "\"\""))

(defn- wrap-in-quotes [s]
  (str "\"" (escape-quotes s) "\""))

(defn- separate [data separator quote?]
  (str/join separator
            (cond->> data
              :always (map str)
              quote? (map wrap-in-quotes))))

(defn- write-data [data separator newline quote?]
  (str/join newline (map #(separate % separator quote?) data)))

(def ^:private newlines
  {:lf "\n" :cr+lf "\r\n"})

(def ^:private newline-error-message
  (str ":newline must be one of [" (str/join "," (keys newlines)) "]"))

(defn write-csv
  "Writes data to String in CSV-format.
  Accepts the following options:
  :separator - field separator
               (default ,)
  :newline   - line separator
               (accepts :lf or :cr+lf)
               (default :lf)
  :quote?    - wrap in quotes
               (default false)"

  {:arglists '([data] [data & options]) :added "0.1.0"}
  [data & options]
  (let [{:keys [separator newline quote?] :or {separator "," newline :lf quote? false}} options]
    (if-let [newline-char (get newlines newline)]
      (write-data data
                  separator
                  newline-char
                  quote?)
      (throw (js/Error. newline-error-message)))))

(defn- -advance
  "Move to the next character."
  [{:keys [chars] :as state}]
  (assoc state
         :char  (first chars)
         :chars (rest chars)))

(defn- -consume
  "Append the current character onto the field. Advances."
  [{:keys [char] :as state}]
  (-> state
      (update :field-buffer str char)
      (-advance)))

(defn- -end-field
  "Finalize the field, adding it to the current row. Does not advance.

  Following convention, a field that hasn't had any chars appended appears as an
  empty string, not nil."
  [{:keys [field-buffer row] :as state}]
  (assoc state
         :field-buffer nil
         :row (conj row (str field-buffer))))

(defn- -end-row
  "Finalize the last field in the row. Then append the row to the collection of
  all rows, and start a new row. Does not advance."
  [state]
  (let [{:keys [row] :as state} (-end-field state)]
    (-> state
        (update :rows conj row)
        (assoc :row []))))

(defn- -init-read
  "Prepare to process the string `data`. Advances to the first character."
  [data]
  (-advance {:chars        (seq data)
             :field-buffer nil
             :row          []
             :rows         []}))

(defn read-csv
  "Reads data from String in CSV-format."
  {:arglists '([data] [data & options]) :added "0.3.0"}
  [data & options]
  (let [{:keys [separator newline] :or {separator "," newline :lf}} options
        ;; convert separator from string to character
        separator (first separator)]
    (when-not (contains? newlines newline)
      (throw (js/Error. newline-error-message)))
    (loop [{:keys [char chars in-quoted-field field-buffer] :as state} (-init-read data)]
      (if-not char
        (:rows (-end-row state))
        ;; NOTE: always advance or consume to avoid infinite loops
        (recur (if in-quoted-field
                 (if (= char \")
                   (if (= (first chars) \")
                     ;; pair of double quotes: use one and drop the other
                     (-> state (-consume) (-advance))
                     (-> state (dissoc :in-quoted-field) (-advance)))
                   (-consume state))
                 (cond
                   ;; first char in field is a quote
                   (and (= char \")
                        (not field-buffer))
                   (-> state (assoc :in-quoted-field true) (-advance))

                   (= char separator)
                   (-> state (-end-field) (-advance))

                   (and (= char \return)
                        (= newline :cr+lf)
                        (= (first chars) \newline))
                   (-> state (-end-row) (-advance) (-advance))

                   (= char \newline)
                   (-> state (-end-row) (-advance))

                   :else
                   (-consume state))))))))
