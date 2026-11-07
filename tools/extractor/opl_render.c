/* 원작 OPL 레지스터 기록을 추출 도구에서 PCM으로 렌더링하는 어댑터. */
#include <stdlib.h>
#include "vendor/nuked-opl3/opl3.h"
void *protocoss_opl_new(unsigned rate) {
    opl3_chip *chip = calloc(1, sizeof(opl3_chip));
    if (chip) OPL3_Reset(chip, rate);
    return chip;
}
void protocoss_opl_write(void *chip, unsigned reg, unsigned value) {
    OPL3_WriteRegBuffered(chip, reg, value);
}
void protocoss_opl_render(void *chip, short *samples, unsigned count) {
    OPL3_GenerateStream(chip, samples, count);
}
void protocoss_opl_free(void *chip) { free(chip); }
