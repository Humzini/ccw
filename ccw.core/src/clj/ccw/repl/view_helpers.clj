(ns ccw.repl.view-helpers
  (:require [clojure.tools.nrepl :as repl]
            [clojure.tools.nrepl.misc :refer (uuid)]
            [ccw.repl.cmdhistory :as history]
            [ccw.events :as evt]
            [ccw.eclipse :as eclipse]
            [ccw.swt :as swt])
  (:import ccw.CCWPlugin
           org.eclipse.ui.PlatformUI
           org.eclipse.swt.SWT
           [org.eclipse.swt.custom StyledText StyleRange]
           org.eclipse.ui.handlers.HandlerUtil))

(defn- set-style-range
  [style-range-fn start length]
  (let [^StyleRange style (style-range-fn)]
    (set! (.start style) start)
    (set! (.length style) length)
    style))

(def ^{:private true} default-log-style (partial set-style-range #(StyleRange.)))

(def ^{:private true} log-styles
  (let [colored-style #(let [s (StyleRange.)
                             color-rgb (CCWPlugin/getPreferenceRGB (.getCombinedPreferenceStore (CCWPlugin/getDefault)) %)
                             color (CCWPlugin/getColor color-rgb)]
                         (when color (set! (.foreground s) color))
                         s)
        error-rgb-key (ccw.preferences.PreferenceConstants/getTokenColorPreferenceKey ccw.preferences.PreferenceConstants/replLogError)
        value-rgb-key (ccw.preferences.PreferenceConstants/getTokenColorPreferenceKey ccw.preferences.PreferenceConstants/replLogValue)]
    {:err [(partial set-style-range #(colored-style error-rgb-key)) nil]
     :out [default-log-style nil]
     :value [(partial set-style-range #(colored-style value-rgb-key)) nil]
     :pprint-out [(partial set-style-range #(colored-style value-rgb-key)) nil]
     :in-expr [:skip   ;; skip :in-expr styling because it's done upstream
               :highlight-background]}))

(defn- cursor-at-end
  "Puts the cursor at the end of the text in the given widget."
  [^StyledText widget]
  (.setCaretOffset widget (.getCharCount widget)))

(defn log
  [^ccw.repl.REPLView repl-view ^StyledText log ^String s type]
  (swt/dosync
    (let [charcnt (.getCharCount log)
          [log-style highlight-background] (get log-styles type [default-log-style nil])
          linecnt (.getLineCount log)
          new-content (if (re-find #"(\n|\r)$" s) s (str s \newline))]
      ; Add styles before adding text to the log panel
      (when-not (= :skip log-style)
        (let [style (log-style charcnt (.length new-content))]
          (-> repl-view .logPanelStyleCache (.setStyleRange style))))
      (.append log new-content)
      (doto log
        cursor-at-end
        .showSelection)
      (when highlight-background
        (.setLineBackground log (dec linecnt) (- (.getLineCount log) linecnt)
          (ccw.CCWPlugin/getColor
            ;; We use RGB color because we cannot take the Color directly since
            ;; we do not "own" it (it would be disposed when colors are changed
            ;; from the preferences, not good)
            (-> repl-view .logPanelEditorColors .fCurrentLineBackgroundColor .getRGB)))))))

(defn eval-failure-msg
  [status s]
  (format "Expression %s: %s"
    ({"timeout" "timed out", "interrupted" "was interrupted"} status "failed")
    (some-> s
      (.substring 0 (min 30 (count s)))
      (str (when (> (count s) 30) "..."))
      (.replaceAll "\\n|\\r" " "))))

(defn handle-responses
  [repl-view log-component expr responses]
  (future
    (doseq [{:keys [out err value ns status] :as resp} responses]
      (evt/post-event :ccw.repl.response resp)
      (swt/dosync
        (when ns (.setCurrentNamespace repl-view ns))
        (doseq [[k v] (dissoc resp :id :ns :status :session)
                :when (log-styles k)]
          (log repl-view log-component v k))
        (doseq [status status]
          (case status
            "interrupted" (log repl-view log-component (eval-failure-msg status expr) :err)
            "need-input" (.getStdIn repl-view)
            nil))))))

(defn eval-expression
  "evaluate expression. Will use the current value for use-pprint and pprint-right-margin
   if `expr` is not already a map."
  [repl-view log-component client expr]
  (try
    (repl/message client
      (if (map? expr)
        expr
        (let [m {:op "eval" :code expr :ns (.getCurrentNamespace repl-view)}]
          (if (and (.usePPrint repl-view) (.isPPrintAvailable repl-view))
            (assoc m :pprint "true" :right-margin (.getPPrintRightMargin repl-view))
            m))))
    (catch Throwable t
      (CCWPlugin/logError (eval-failure-msg nil expr) t)
      (log repl-view log-component (eval-failure-msg nil expr) :err))))

(defn configure-repl-view
  [repl-view log-panel repl-client session-id]
  (let [[history retain-expr-fn] (history/get-history (some-> repl-view
                                                        .getLaunch
                                                        ccw.launching.LaunchUtils/getProjectName))
        ^StyledText input-widget (.inputStyledText repl-view)
        ; a bunch of atoms are just fine, since access to them is already
        ; serialized via the SWT event thread
        history (atom history)
        current-step (atom -1)
        retained-input (atom nil)
        history-action-fn
        (fn [history-shift]
          (swap! current-step history-shift)
          (cond
            (>= @current-step (count @history)) (do (swap! current-step dec) (swt/beep))
            (neg? @current-step) (do (reset! current-step -1)
                                   (when @retained-input
                                     (doto input-widget
                                       (.setText @retained-input)
                                       cursor-at-end)
                                     (reset! retained-input nil)))
            :else (do
                    (when-not @retained-input
                      (reset! retained-input (.getText input-widget)))
                    (doto input-widget
                      (.setText (@history (dec (- (count @history) @current-step))))
                      cursor-at-end))))
        session-client (repl/client-session repl-client :session session-id)
        responses-promise (promise)]
    (.setHistoryActionFn repl-view history-action-fn)

    ;; TODO need to make client-session accept a single arg to avoid
    ;; dummy message sends
    (handle-responses repl-view log-panel nil (session-client {:op "describe"}))

    (comp (partial eval-expression repl-view log-panel session-client)
      (fn [expr add-to-history?]
        (reset! retained-input nil)
        (reset! current-step -1)
        (when add-to-history?
          (swap! history #(subvec
                            ; don't add duplicate expressions to the history
                            (if (= expr (last %))
                              %
                              (conj % expr))
                            (-> % count (- (history/max-history)) (max 0))))
          (retain-expr-fn expr))
        expr))))

(defn- load-history [event history-shift]
  (let [repl-view (HandlerUtil/getActivePartChecked event)]
    ((.getHistoryActionFn repl-view) history-shift)))

(defn history-previous [_ event] (load-history event inc))
(defn history-next [_ event] (load-history event dec))
