import React from 'react';
import { View, StyleSheet } from 'react-native';
import { NitroPdf } from 'react-native-nitro-pdf';

function App(): React.JSX.Element {
  return (
    <View style={styles.container}>
        <NitroPdf isRed={true} style={styles.view} testID="nitro-pdf" />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  view: {
    width: 200,
    height: 200
  }});

export default App;