using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using FableBrowser.Browser;
using FableBrowser.Core;
using Microsoft.Web.WebView2.Core;

namespace FableBrowser.UI;

public static class Dialogs
{
    static ImageSource? icon;

    public static ImageSource AppIcon => icon ??= BitmapFrame.Create(new Uri("pack://application:,,,/Assets/fable.ico", UriKind.Absolute));

    public static void Decorate(Window window)
    {
        window.Icon = AppIcon;
        window.SetResourceReference(Control.BackgroundProperty, "FbWindow");
        window.SetResourceReference(Control.ForegroundProperty, "FbText");
        window.UseLayoutRounding = true;
        window.SourceInitialized += (_, _) => NativeMethods.UseDarkTitleBar(window, App.IsDarkTheme);
    }

    public static Window Create(Window? owner, string title, double width)
    {
        var window = new Window
        {
            Title = title,
            Width = width,
            SizeToContent = SizeToContent.Height,
            ResizeMode = ResizeMode.NoResize,
            ShowInTaskbar = false,
            WindowStartupLocation = owner != null ? WindowStartupLocation.CenterOwner : WindowStartupLocation.CenterScreen
        };
        if (owner != null && owner.IsLoaded) window.Owner = owner;
        Decorate(window);
        return window;
    }

    public static TextBlock Heading(string text)
    {
        var block = new TextBlock { Text = text, FontSize = 17, FontWeight = FontWeights.SemiBold, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 8) };
        block.SetResourceReference(TextBlock.ForegroundProperty, "FbText");
        return block;
    }

    public static TextBlock Body(string text)
    {
        var block = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, FontSize = 13.5 };
        block.SetResourceReference(TextBlock.ForegroundProperty, "FbSubtle");
        return block;
    }

    public static StackPanel ButtonRow(params Button[] buttons)
    {
        var row = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 20, 0, 0) };
        foreach (var button in buttons) row.Children.Add(button);
        return row;
    }

    public static Button Primary(string text)
    {
        var button = new Button { Content = text, MinWidth = 96, Margin = new Thickness(8, 0, 0, 0), IsDefault = true };
        button.Style = (Style)Application.Current.FindResource("AccentButton");
        return button;
    }

    public static Button Secondary(string text, bool cancel = false)
    {
        var button = new Button { Content = text, MinWidth = 96, Margin = new Thickness(8, 0, 0, 0), IsCancel = cancel, Padding = new Thickness(14, 6, 14, 6) };
        return button;
    }
}

public static class PermissionDialog
{
    public readonly record struct Answer(bool Allow, bool Remember);

    static string Describe(CoreWebView2PermissionKind kind) => kind switch
    {
        CoreWebView2PermissionKind.Camera => "Use your camera",
        CoreWebView2PermissionKind.Microphone => "Use your microphone",
        CoreWebView2PermissionKind.Geolocation => "Know your location",
        CoreWebView2PermissionKind.Notifications => "Show notifications",
        CoreWebView2PermissionKind.OtherSensors => "Use motion and light sensors",
        CoreWebView2PermissionKind.ClipboardRead => "See text and images you copy to the clipboard",
        CoreWebView2PermissionKind.LocalFonts => "See the fonts installed on this PC. Sites can use this to fingerprint you.",
        CoreWebView2PermissionKind.MidiSystemExclusiveMessages => "Get full control of your MIDI devices",
        CoreWebView2PermissionKind.WindowManagement => "Open and place windows on all your displays",
        CoreWebView2PermissionKind.MultipleAutomaticDownloads => "Download several files automatically",
        _ => "Use a special browser feature"
    };

    public static Answer Ask(Window owner, string origin, CoreWebView2PermissionKind kind, bool isPrivate)
    {
        var window = Dialogs.Create(owner, "Permission request", 460);
        var panel = new StackPanel { Margin = new Thickness(24, 20, 24, 20) };
        var host = UrlUtils.DisplayHost(origin);
        panel.Children.Add(Dialogs.Heading((host.Length > 0 ? host : origin) + " wants to"));
        panel.Children.Add(Dialogs.Body(Describe(kind)));
        var remember = new CheckBox
        {
            Content = isPrivate ? "Remember while this private tab is open" : "Remember my choice for this site",
            IsChecked = true,
            Margin = new Thickness(0, 16, 0, 0)
        };
        panel.Children.Add(remember);
        var allow = false;
        var block = Dialogs.Secondary("Block", true);
        var permit = Dialogs.Primary("Allow");
        permit.IsDefault = false;
        block.Click += (_, _) => window.DialogResult = false;
        permit.Click += (_, _) =>
        {
            allow = true;
            window.DialogResult = true;
        };
        panel.Children.Add(Dialogs.ButtonRow(block, permit));
        window.Content = panel;
        window.ShowDialog();
        return new Answer(allow, remember.IsChecked == true);
    }
}

public static class TextPrompt
{
    public static string? Ask(Window owner, string title, string prompt, string initial)
    {
        var window = Dialogs.Create(owner, title, 420);
        var panel = new StackPanel { Margin = new Thickness(24, 20, 24, 20) };
        panel.Children.Add(Dialogs.Body(prompt));
        var box = new TextBox { Text = initial, Margin = new Thickness(0, 10, 0, 0), Padding = new Thickness(6, 4, 6, 4) };
        panel.Children.Add(box);
        var ok = Dialogs.Primary("OK");
        var cancel = Dialogs.Secondary("Cancel", true);
        string? result = null;
        ok.Click += (_, _) =>
        {
            result = box.Text;
            window.DialogResult = true;
        };
        panel.Children.Add(Dialogs.ButtonRow(cancel, ok));
        window.Content = panel;
        window.Loaded += (_, _) =>
        {
            box.Focus();
            box.SelectAll();
        };
        window.ShowDialog();
        return result;
    }
}

public static class PinDialog
{
    public static string? AskNewPin(Window owner)
    {
        var window = Dialogs.Create(owner, "Set PIN", 400);
        var panel = new StackPanel { Margin = new Thickness(24, 20, 24, 20) };
        panel.Children.Add(Dialogs.Heading("Choose a PIN for private tabs"));
        panel.Children.Add(Dialogs.Body("At least 4 characters. It is stored only as a salted hash on this PC."));
        var first = new PasswordBox { Margin = new Thickness(0, 14, 0, 0), Height = 32 };
        var second = new PasswordBox { Margin = new Thickness(0, 8, 0, 0), Height = 32 };
        var error = new TextBlock { Margin = new Thickness(0, 8, 0, 0) };
        error.SetResourceReference(TextBlock.ForegroundProperty, "FbDanger");
        panel.Children.Add(first);
        panel.Children.Add(second);
        panel.Children.Add(error);
        string? result = null;
        var ok = Dialogs.Primary("Save");
        var cancel = Dialogs.Secondary("Cancel", true);
        ok.Click += (_, _) =>
        {
            if (first.Password.Length < 4)
            {
                error.Text = "The PIN is too short.";
                return;
            }
            if (first.Password != second.Password)
            {
                error.Text = "The two PINs do not match.";
                return;
            }
            result = first.Password;
            window.DialogResult = true;
        };
        panel.Children.Add(Dialogs.ButtonRow(cancel, ok));
        window.Content = panel;
        window.Loaded += (_, _) => first.Focus();
        window.ShowDialog();
        return result;
    }
}
