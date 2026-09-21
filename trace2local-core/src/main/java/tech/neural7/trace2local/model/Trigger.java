package tech.neural7.trace2local.model;

/** Como a execução começou (SPEC §4.6). */
public enum Trigger {
    /** Disparo pelo botão EXECUTE REQUEST da UI. */
    UI_DISPATCH,
    /** Tráfego externo sem participação do launcher. */
    EXTERNAL,
    /** Invocação de função Lambda (modo Companion). */
    LAMBDA_EVENT,
    /** Teste de integração via trace2local-testing. */
    TEST
}
