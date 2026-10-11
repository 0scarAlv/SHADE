using NAudio.CoreAudioApi;

namespace Shade.Agent.Audio;

// Wraps the default render endpoint's IAudioEndpointVolume (Core Audio API via
// NAudio). VolumeChanged also fires for changes made outside the app — the
// hardware buttons, another app, Windows' own volume mixer — so the agent can
// broadcast a fresh state instead of only reacting to its own commands.
public sealed class SystemVolumeController : IDisposable
{
    private const float Step = 0.05f;

    private readonly MMDevice _device;

    public event Action? VolumeChanged;

    public SystemVolumeController()
    {
        using var enumerator = new MMDeviceEnumerator();
        _device = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        _device.AudioEndpointVolume.OnVolumeNotification += _ => VolumeChanged?.Invoke();
    }

    public double GetVolume() => _device.AudioEndpointVolume.MasterVolumeLevelScalar;

    public bool GetMuted() => _device.AudioEndpointVolume.Mute;

    // Absolute set, driven by the panel's volume slider. Unmutes on the way —
    // dragging the slider while muted and hearing nothing would read as broken.
    public void SetVolume(double level)
    {
        _device.AudioEndpointVolume.MasterVolumeLevelScalar = (float)Math.Clamp(level, 0d, 1d);
        if (_device.AudioEndpointVolume.Mute) _device.AudioEndpointVolume.Mute = false;
    }

    public void ToggleMute() => _device.AudioEndpointVolume.Mute = !_device.AudioEndpointVolume.Mute;

    public void VolumeUp() => Adjust(Step);

    public void VolumeDown() => Adjust(-Step);

    private void Adjust(float delta)
    {
        var current = _device.AudioEndpointVolume.MasterVolumeLevelScalar;
        _device.AudioEndpointVolume.MasterVolumeLevelScalar = Math.Clamp(current + delta, 0f, 1f);
    }

    public void Dispose() => _device.Dispose();
}
