#version 330
#extension GL_ARB_separate_shader_objects : require

// Motion Blur: each frame mixed with the blurred frames before it, Strength.x of the old kept.

uniform sampler2D InSampler;
uniform sampler2D PrevSampler;

layout(location = 0) in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform BlurConfig {
    vec4 Strength;
};

layout(location = 0) out vec4 fragColor;

void main() {
    vec3 now = texture(InSampler, texCoord).rgb;
    vec3 before = texture(PrevSampler, texCoord).rgb;
    fragColor = vec4(mix(now, before, Strength.x), 1.0);
}
