#version 330

// Pink World: recolours every pixel towards Tint.rgb at its own brightness, by Tint.a.

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform PinkConfig {
    vec4 Tint;
};

out vec4 fragColor;

const vec3 Luma = vec3(0.299, 0.587, 0.114);

void main() {
    vec3 color = texture(InSampler, texCoord).rgb;
    float brightness = dot(color, Luma);
    vec3 tinted = Tint.rgb * (brightness / max(dot(Tint.rgb, Luma), 0.001));
    // Very bright pixels would clip; spill the excess into the other channels so they fade to white
    float excess = max(max(tinted.r, max(tinted.g, tinted.b)) - 1.0, 0.0);
    tinted = min(tinted + excess, vec3(1.0));
    fragColor = vec4(mix(color, tinted, Tint.a), 1.0);
}
