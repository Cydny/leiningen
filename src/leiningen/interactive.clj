(ns leiningen.interactive
  "Enter interactive shell for calling tasks without relaunching JVM."
  (:require [clojure.string :as string])
  (:use [leiningen.core :only [apply-task exit]]
        [leiningen.compile :only [*exit-when-done*]]
        [leiningen.repl :only [repl-server repl-socket-on
                               copy-out-loop poll-repl-connection]]
        [leiningen.compile :only [eval-in-project]]))

(def welcome "Welcome to Leiningen. Type help for a list of commands.")

(def prompt "lein> ")

(defn not-found [& _]
  (println "That's not a task. Use help to list all tasks."))

(defn- eval-client-loop [reader writer buffer socket]
  (let [len (.read reader buffer)
        output (String. buffer)]
    (when-not (neg? len)
      (.write *out* buffer 0 len)
      (flush)
      (when-not (.isClosed socket)
        (Thread/sleep 100)
        (recur reader writer buffer socket)))))

(defn eval-in-repl [connect project form & [args]]
  (let [[reader writer socket] (connect)]
    (.write writer (str (pr-str form) "\n" '(.close *in*) "\n"))
    (.flush writer)
    (try (eval-client-loop reader writer
                           (make-array Character/TYPE 1000) socket)
         0
         (catch Exception e
           (.printStackTrace e) 1)
         (finally
          (.close reader)
          (.close writer)))))

(defn print-prompt []
  (print prompt)
  (flush))

(defn task-repl [project]
  (print-prompt)
  (loop [input (.readLine *in*)]
    (when (and input (not= input "exit"))
      (let [[task-name & args] (string/split input #"\s")]
        ;; TODO: don't start a second repl server for repl task
        (apply-task task-name project args not-found)
        (print-prompt)
        (recur (.readLine *in*))))))

(defn interactive
  "Enter an interactive shell for calling tasks without relaunching JVM."
  [project]
  (let [[port host] (repl-socket-on project)]
    (println welcome)
    (future
      (binding [*exit-when-done* false]
        (eval-in-project project `(do ~(repl-server project host port :silently
                                                    :prompt '(constantly ""))
                                      (symbol "")))))
    (let [connect #(poll-repl-connection port 0 vector)]
      (binding [eval-in-project (partial eval-in-repl connect)
                exit (fn [_] (println "\n"))]
        (task-repl project)))
    (exit)))
