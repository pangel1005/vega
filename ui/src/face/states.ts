import type { AssistantState } from "../net/protocol";

/** Texto que acompaña a cada estado: japonés (estética NERV) + español claro. */
export const STATE_LABELS: Record<AssistantState, { jp: string; es: string }> = {
  idle: { jp: "待機", es: "En espera" },
  listening: { jp: "受信", es: "Escuchando" },
  thinking: { jp: "審議中", es: "Pensando" },
  speaking: { jp: "発声", es: "Respondiendo" },
  acting: { jp: "実行", es: "Ejecutando" },
  error: { jp: "非常事態", es: "Error" },
};
